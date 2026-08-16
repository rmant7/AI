package ai.localstudio.core.registry

import ai.localstudio.core.GB
import ai.localstudio.core.binding
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.device
import ai.localstudio.core.model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SuitabilityScorerTest {

    private val scorer = SuitabilityScorer()

    @Test
    fun `model exceeding the usable ram budget is incompatible`() {
        // 10 GB free * 0.6 safety = 6 GB usable.
        val device = device(availableRamBytes = 10 * GB)
        val heavy = model("heavy", bindings = listOf(binding(ramBytes = 7 * GB)))

        val result = scorer.evaluate(heavy, device, Capability.TEXT_GENERATION)

        val incompatible = assertIs<Suitability.Incompatible>(result)
        assertTrue(IncompatibilityReason.NOT_ENOUGH_RAM in incompatible.reasons)
    }

    @Test
    fun `capability is a hard filter`() {
        val result = scorer.evaluate(model("text"), device(), Capability.SPEECH_TO_TEXT)

        assertEquals(
            setOf(IncompatibilityReason.CAPABILITY_NOT_SUPPORTED),
            assertIs<Suitability.Incompatible>(result).reasons,
        )
    }

    @Test
    fun `binding for an unsupported runtime is rejected, another binding saves the model`() {
        val device = device(supportedRuntimes = setOf(RuntimeKind.LLAMA_CPP))
        val dualRuntime = model(
            "dual",
            bindings = listOf(
                binding(runtime = RuntimeKind.MEDIAPIPE, requiresGpu = true),
                binding(runtime = RuntimeKind.LLAMA_CPP),
            ),
        )

        val result = scorer.evaluate(dualRuntime, device, Capability.TEXT_GENERATION)

        assertEquals(RuntimeKind.LLAMA_CPP, assertIs<Suitability.Compatible>(result).binding.runtime)
    }

    @Test
    fun `each runtime's own verdict is kept separate`() {
        val device = device(
            availableRamBytes = 10 * GB, // 6 GB usable
            supportedRuntimes = setOf(RuntimeKind.LLAMA_CPP, RuntimeKind.MEDIAPIPE),
        )
        val model = model(
            "two-ways",
            bindings = listOf(
                binding(runtime = RuntimeKind.LLAMA_CPP, ramBytes = 8 * GB),
                binding(runtime = RuntimeKind.MEDIAPIPE, ramBytes = 2 * GB, requiresGpu = true),
            ),
        )

        val result = assertIs<Suitability.Incompatible>(
            scorer.evaluate(model, device, Capability.TEXT_GENERATION),
        )

        assertEquals(
            setOf(IncompatibilityReason.NOT_ENOUGH_RAM),
            result.byRuntime.getValue(RuntimeKind.LLAMA_CPP),
        )
        assertEquals(
            setOf(IncompatibilityReason.GPU_REQUIRED),
            result.byRuntime.getValue(RuntimeKind.MEDIAPIPE),
        )
        // The union is still available for a one-line summary.
        assertEquals(
            setOf(IncompatibilityReason.NOT_ENOUGH_RAM, IncompatibilityReason.GPU_REQUIRED),
            result.reasons,
        )
    }

    @Test
    fun `installed models are not filtered on storage`() {
        val device = device(availableStorageBytes = 1 * GB)
        val big = model("big", bindings = listOf(binding(fileSizeBytes = 3 * GB, ramBytes = 3 * GB)))

        assertIs<Suitability.Incompatible>(scorer.evaluate(big, device, Capability.TEXT_GENERATION))
        assertIs<Suitability.Compatible>(
            scorer.evaluate(big, device, Capability.TEXT_GENERATION, alreadyInstalled = true),
        )
    }

    @Test
    fun `at equal cost the better benchmark wins`() {
        val models = listOf(
            model("weaker", benchmarks = Benchmarks(reasoning = 70.0)),
            model("stronger", benchmarks = Benchmarks(reasoning = 85.0)),
        )

        val ranked = scorer.rank(models, device(), Capability.REASONING)

        assertEquals(listOf("stronger", "weaker"), ranked.map { it.model.id })
    }

    @Test
    fun `a model that fills the budget and crawls ranks last despite the best benchmarks`() {
        val device = device(availableRamBytes = 10 * GB) // 6 GB usable
        val models = listOf(
            model("small", benchmarks = Benchmarks(reasoning = 70.0), bindings = listOf(binding(ramBytes = 1 * GB, tps = 30.0))),
            model("mid", benchmarks = Benchmarks(reasoning = 85.0), bindings = listOf(binding(ramBytes = 3 * GB, tps = 20.0))),
            model("edge", benchmarks = Benchmarks(reasoning = 88.0), bindings = listOf(binding(ramBytes = 5_800_000_000, tps = 6.0))),
        )

        val ranked = scorer.rank(models, device, Capability.REASONING)

        assertEquals("edge", ranked.last().model.id)
        assertTrue(ranked.first().score > 2 * ranked.last().score)
    }

    @Test
    fun `rank returns at most the requested number of models`() {
        val models = (1..9).map { model("m$it", bindings = listOf(binding(ramBytes = 1 * GB))) }

        assertEquals(5, scorer.rank(models, device(), Capability.TEXT_GENERATION).size)
        assertEquals(3, scorer.rank(models, device(), Capability.TEXT_GENERATION, limit = 3).size)
    }

    @Test
    fun `the top list is device-specific`() {
        val models = listOf(
            model("small", benchmarks = Benchmarks(reasoning = 70.0), bindings = listOf(binding(ramBytes = 1 * GB, tps = 30.0))),
            model("mid", benchmarks = Benchmarks(reasoning = 85.0), bindings = listOf(binding(ramBytes = 3 * GB, tps = 20.0))),
        )

        val flagship = scorer.rank(models, device(availableRamBytes = 10 * GB), Capability.REASONING)
        val budgetPhone = scorer.rank(models, device(availableRamBytes = 4 * GB), Capability.REASONING)

        assertEquals(setOf("small", "mid"), flagship.map { it.model.id }.toSet())
        assertEquals(listOf("small"), budgetPhone.map { it.model.id })
    }

    @Test
    fun `estimated speed scales with the device performance index`() {
        val fast = scorer.evaluate(model("m"), device(performanceIndex = 2.0), Capability.TEXT_GENERATION)
        val slow = scorer.evaluate(model("m"), device(performanceIndex = 0.5), Capability.TEXT_GENERATION)

        assertEquals(40.0, assertIs<Suitability.Compatible>(fast).estimatedTokensPerSecond)
        assertEquals(10.0, assertIs<Suitability.Compatible>(slow).estimatedTokensPerSecond)
        assertTrue(
            assertIs<Suitability.Compatible>(fast).breakdown.total >
                assertIs<Suitability.Compatible>(slow).breakdown.total,
        )
    }
}
