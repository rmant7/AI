package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.GenericFacet
import ai.localstudio.model.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CandidateVerificationTest {

    @Test
    fun a_candidate_with_no_verification_is_unverified() {
        assertEquals(CandidateTier.UNVERIFIED, null.tier())
    }

    private fun verification(loaded: Boolean, inferenceOk: Boolean) = DeviceVerification(
        deviceProfile = "Pixel 10 Pro / API 37 / 16.3 GB / llama.cpp b10448 (i8mm)",
        runtimeId = "llama_cpp",
        loaded = loaded,
        inferenceOk = inferenceOk,
        verifiedAtEpochMs = 1_000L,
    )

    @Test
    fun a_load_failure_is_unverified_not_loadable() {
        assertEquals(CandidateTier.UNVERIFIED, verification(loaded = false, inferenceOk = false).tier())
    }

    @Test
    fun loaded_but_no_answer_is_loadable_not_functional() {
        assertEquals(CandidateTier.LOADABLE, verification(loaded = true, inferenceOk = false).tier())
    }

    @Test
    fun a_real_answer_is_functional() {
        assertEquals(CandidateTier.FUNCTIONAL, verification(loaded = true, inferenceOk = true).tier())
    }

    @Test
    fun inference_ok_implies_functional_even_if_loaded_was_recorded_wrong() {
        // inferenceOk can only be true after a real load, so it alone decides --
        // a caller's own bug in setting `loaded` must never hide a real answer.
        assertEquals(CandidateTier.FUNCTIONAL, verification(loaded = false, inferenceOk = true).tier())
    }

    @Test
    fun the_install_model_pins_the_exact_file_discovery_already_probed_never_quant_priority() {
        val model = CandidateModel.of(
            repoId = "acme/small-GGUF",
            commit = "a".repeat(40),
            filePath = "small-Q4_K_M.gguf",
            sizeBytes = 2_000_000_000,
            sha256 = "b".repeat(64),
            variantId = "discovered-acme-small",
            capability = Capabilities.TEXT_GENERATION,
            capabilityFacet = GenericFacet(),
            runtime = RuntimeId("llama_cpp"),
        )

        assertEquals(CatalogStatus.UNVERIFIED, model.status, "a discovery candidate is never anything else")
        val variant = model.variants.single()
        val weights = variant.artifacts.single { it.role == ArtifactRoles.WEIGHTS }
        val source = assertIs<ArtifactSource.HuggingFace>(weights.source)
        assertEquals("acme/small-GGUF", source.repo)
        assertEquals("a".repeat(40), source.revision, "the commit discovery resolved, not a branch to re-resolve later")
        assertEquals("small-Q4_K_M.gguf", source.path, "the exact file GgufProbe read, never re-picked by quant priority")
        assertEquals(2_000_000_000, weights.sizeBytes)
        assertEquals("b".repeat(64), weights.sha256)
        assertEquals(setOf(ArtifactRoles.WEIGHTS), variant.bindings.single().requiredRoles)
    }
}
