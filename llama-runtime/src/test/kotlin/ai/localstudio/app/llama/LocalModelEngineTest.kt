package ai.localstudio.app.llama

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.InsufficientMemoryException
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelRuntime
import ai.localstudio.core.runtime.WeightsLoading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * [LocalModelEngine]'s bookkeeping on a plain JVM: what a load is admitted
 * against, which measurement applies to which load, and the weights load
 * mode. Loads go through a fake runtime -- the native one runs on a device.
 */
class LocalModelEngineTest {

    private val dir: File = Files.createTempDirectory("engine").toFile()

    /** A sparse file of [bytes]: only its size matters here. */
    private fun model(name: String, bytes: Long) = File(dir, name).apply {
        RandomAccessFile(this, "rw").use { it.setLength(bytes) }
    }

    private var setting = WeightsLoading.AUTO
    private var budget = 16_000_000_000L

    private fun engine(store: KeyValueStore = InMemoryKeyValueStore(), runtime: String = "llama.cpp b10448 / jni 3") = LocalModelEngine(
        runtimeVersion = runtime,
        ramStore = store,
        availableRamBytes = { budget },
        budgetBytes = { budget },
        weightsLoading = { setting },
    )

    private class FakeLoaded(override val modelId: String, override val ramBytes: Long) : LoadedModel {
        override fun close() {}
    }

    private class FakeRuntime : ModelRuntime {
        override val kind = RuntimeKind.LLAMA_CPP
        val loads = mutableListOf<String>()
        override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
        override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel {
            loads += model.id
            return FakeLoaded(model.id, binding.fileSizeBytes)
        }
    }

    @Test
    fun an_unmeasured_model_is_admitted_on_its_size_times_1_3() {
        val weights = model("a.gguf", 1_000_000_000)
        assertEquals(1_300_000_000L, engine().admissionBytes(weights, 4096))
    }

    @Test
    fun a_measurement_replaces_the_estimate_for_its_own_context_size_only() {
        val weights = model("a.gguf", 1_000_000_000)
        val engine = engine()
        val measured = engine.measuredRam.record(weights.absolutePath, 4096, 2_000_000_000)!!
        assertEquals(measured.requiredBytes, engine.admissionBytes(weights, 4096))
        // #492: a figure taken at 2048 tokens never stands in for 4096, nor the other way round.
        assertEquals(1_300_000_000L, engine.admissionBytes(weights, 2048))
    }

    @Test
    fun mapped_and_in_memory_loads_are_measured_apart() {
        val weights = model("a.gguf", 1_000_000_000)
        val engine = engine()
        setting = WeightsLoading.MAPPED
        engine.measuredRam.record(weights.absolutePath, 4096, 2_000_000_000)
        setting = WeightsLoading.IN_MEMORY
        assertEquals(1_300_000_000L, engine.admissionBytes(weights, 4096))
        setting = WeightsLoading.MAPPED
        assertTrue(engine.admissionBytes(weights, 4096) > 2_000_000_000)
    }

    @Test
    fun auto_maps_until_measured_then_reads_a_copied_model_into_memory() {
        val weights = model("a.gguf", 4_000_000_000)
        val engine = engine()
        assertTrue("not measured yet: mapped", engine.weightsLoadDecision(weights).mapped)
        // Qwen2.5-VL-7B, #490: anonymous growth 0.90 of the file when mapped -- a copy anyway.
        engine.measuredRam.recordMappedAnonymous(weights.absolutePath, 3_600_000_000)
        assertFalse(engine.weightsLoadDecision(weights).mapped)
        // Gemma 4 E2B: 0.19 -- the mapped pages really carry the weights.
        engine.measuredRam.recordMappedAnonymous(weights.absolutePath, 760_000_000)
        assertTrue(engine.weightsLoadDecision(weights).mapped)
    }

    @Test
    fun a_figure_measured_by_another_runtime_is_not_reused() {
        val weights = model("a.gguf", 1_000_000_000)
        val store = InMemoryKeyValueStore()
        engine(store, runtime = "llama.cpp b10448 / jni 2").measuredRam.record(weights.absolutePath, 4096, 2_000_000_000)
        assertNull(engine(store, runtime = "llama.cpp b10448 / jni 3").measuredRam.measurementFor(weights.absolutePath, 4096))
    }

    @Test
    fun a_file_replaced_under_the_same_name_is_measured_again() {
        val weights = model("a.gguf", 1_000_000_000)
        val engine = engine()
        engine.measuredRam.record(weights.absolutePath, 4096, 2_000_000_000)
        weights.setLastModified(weights.lastModified() + 60_000)
        assertNull(engine.measuredRam.measurementFor(weights.absolutePath, 4096))
        assertEquals(1_300_000_000L, engine.admissionBytes(weights, 4096))
    }

    @Test
    fun a_check_drops_every_figure_of_its_file() {
        val weights = model("a.gguf", 1_000_000_000)
        val engine = engine()
        engine.measuredRam.record(weights.absolutePath, 2048, 1_800_000_000)
        engine.measuredRam.record(weights.absolutePath, 4096, 2_000_000_000)
        assertEquals(2, engine.measuredRam.forgetAll(weights.absolutePath))
        assertTrue(engine.measuredRam.measurementsOf(weights.absolutePath).isEmpty())
    }

    @Test
    fun a_model_that_does_not_fit_is_refused_typed_and_never_loaded() = runBlocking {
        val weights = model("big.gguf", 4_000_000_000)
        val engine = engine()
        engine.measuredRam.record(weights.absolutePath, 4096, 7_000_000_000)
        budget = 6_000_000_000
        val runtime = FakeRuntime()
        val descriptor = EngineModel("big", weights).descriptor()
        try {
            engine.manager.withModel(descriptor, descriptor.bindings.single(), runtime, 4096) { }
            fail("admitted past its measured cost")
        } catch (e: InsufficientMemoryException) {
            assertTrue(e.requestedBytes > 7_000_000_000)
        }
        assertTrue("no native load attempted", runtime.loads.isEmpty())
    }

    @Test
    fun loading_another_model_evicts_the_idle_one_and_a_projector_is_reserved_on_top() = runBlocking {
        val first = EngineModel("first", model("first.gguf", 1_000_000_000)).descriptor()
        val second = EngineModel("second", model("second.gguf", 1_000_000_000), projector = model("p.gguf", 500_000_000)).descriptor()
        val engine = engine()
        val runtime = FakeRuntime()
        engine.manager.withModel(first, first.bindings.single(), runtime, 4096) { }
        engine.manager.withModel(second, second.bindings.single(), runtime, 4096) {
            engine.manager.reserve("second", 700_000_000, "vision projector")
            assertEquals(listOf("second"), engine.manager.residentModels().map { it.modelId })
            assertEquals(1_700_000_000L, engine.manager.residentBytes)
        }
        budget = 1_200_000_000
        engine.manager.withModel(second, second.bindings.single(), runtime, 4096) {
            try {
                engine.manager.reserve("second", 700_000_000, "vision projector")
                fail("a projector that does not fit must be refused")
            } catch (e: InsufficientMemoryException) {
                // expected: weights + projector over the budget
            }
        }
    }

    @Test
    fun admission_reads_the_rule_a_load_applies_without_loading_anything() = runBlocking {
        // IntelliVerse #169: Gemma 4 E4B needed 6401 MB with 5861 MB available -- refused at the load.
        val e4b = model("e4b.gguf", 4_980_000_000)
        val small = model("small.gguf", 2_500_000_000)
        val engine = engine()
        engine.measuredRam.record(e4b.absolutePath, 4096, 5_334_000_000)
        budget = 5_861_000_000
        val refused = engine.admission(EngineModel("e4b", e4b), 4096)
        assertTrue(refused is Admission.NotAdmitted)
        assertEquals(engine.admissionBytes(e4b, 4096), refused.requiredBytes)
        assertEquals(5_861_000_000L, refused.availableBytes)
        assertTrue(engine.admission(EngineModel("small", small), 4096) is Admission.Admitted)
        assertTrue(engine.manager.residentModels().isEmpty())
    }

    @Test
    fun a_model_resident_as_asked_is_admitted_as_it_is() = runBlocking {
        val weights = EngineModel("a", model("a.gguf", 1_000_000_000))
        val engine = engine()
        engine.manager.withModel(weights.descriptor(), weights.descriptor().bindings.single(), FakeRuntime(), 4096) { }
        budget = 100
        assertEquals(true, (engine.admission(weights, 4096) as? Admission.Admitted)?.resident)
        assertTrue("another context size is another load", engine.admission(weights, 2048) is Admission.NotAdmitted)
    }
}
