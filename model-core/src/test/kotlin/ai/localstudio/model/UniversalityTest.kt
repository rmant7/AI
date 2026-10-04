package ai.localstudio.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The point of the unified domain: five model kinds that today each have
 * their own seed type, store and downloader — a GGUF LLM, a Whisper model,
 * a TTS voice pack, an embedding model, a vision GGUF with a projector —
 * plus one capability this build has never heard of, all described, parsed,
 * validated and matched by the same code, with no per-kind branch anywhere.
 */
class UniversalityTest {

    private val catalog = Fixtures.sample()

    @Test
    fun `every model kind lives in one catalog and passes the same validation`() {
        assertEquals(emptyList(), catalog.violations)
        assertEquals(
            setOf(
                "translategemma-4b-it", "gemma-4-e4b-it", "whisper-base",
                "piper-en-us-amy-low", "multilingual-e5-small", "future-video-gen",
            ),
            catalog.models.map { it.id.id }.toSet(),
        )
    }

    @Test
    fun `artifact shapes differ wildly, the description doesn't`() {
        fun roles(variantId: String) = catalog.models.flatMap { it.variants }.single { it.id.id == variantId }.roles
        assertEquals(setOf(ArtifactRoles.WEIGHTS), roles("whisper-base@ggml"))
        assertEquals(setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), roles("gemma-4-e4b-it@q4_k_m"))
        assertEquals(setOf(ArtifactRoles.ARCHIVE), roles("piper-en-us-amy-low@sherpa"))
        assertEquals(setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.ONNX_DATA, ArtifactRole("vae")), roles("future-video-gen@fp16"))
    }

    @Test
    fun `one runtime serves several model kinds and unknown runtimes are just ids`() {
        val byRuntime = catalog.models.flatMap { model -> model.variants.flatMap { v -> v.bindings.map { it.runtime to model.id.id } } }
            .groupBy({ it.first }, { it.second })
        assertEquals(
            setOf("translategemma-4b-it", "gemma-4-e4b-it", "multilingual-e5-small"),
            byRuntime.getValue(Runtimes.LLAMA_CPP).toSet(),
        )
        assertTrue(RuntimeId("some_future_runtime") in byRuntime)
    }

    @Test
    fun `every capability in the catalog is reachable through the same matcher`() {
        val capabilities = catalog.models.flatMap { it.capabilities.keys }.toSet()
        for (capability in capabilities) {
            val matched = RequirementMatcher.matching(catalog.models, ModelRequirement(capability))
            assertTrue(matched.isNotEmpty(), "nothing matched $capability")
        }
        assertTrue(CapabilityId("video.generation") in capabilities)
    }
}
