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

    private fun verification(
        loaded: Boolean,
        inferenceOk: Boolean,
        checkVersion: Int = DeviceVerification.CURRENT_CHECK,
        checks: Map<String, CapabilityCheck> = if (inferenceOk) mapOf(VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.PASS)) else emptyMap(),
    ) = DeviceVerification(
        deviceProfile = "Pixel 10 Pro / API 37 / 16.3 GB / llama.cpp b10448 (i8mm)",
        runtimeId = "llama_cpp",
        loaded = loaded,
        inferenceOk = inferenceOk,
        verifiedAtEpochMs = 1_000L,
        checkVersion = checkVersion,
        checks = checks,
    )

    @Test
    fun a_translation_model_failing_chat_questions_is_functional_for_translation_only() {
        val v = verification(
            loaded = true, inferenceOk = true,
            checks = mapOf(
                VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.FAIL, "wrong answer"),
                VerifiedCapability.TRANSLATION to CapabilityCheck(CheckStatus.PASS),
            ),
        )
        assertEquals(CandidateTier.FUNCTIONAL, v.tier())
        assertEquals(listOf(VerifiedCapability.TRANSLATION), v.passed)
        assertEquals(CheckStatus.NOT_TESTED, v.status(VerifiedCapability.VISION))
    }

    @Test
    fun a_pass_recorded_by_the_previous_check_version_is_not_trusted() {
        // Version 2 judged translation with a chat-style prompt the Translation screen never sends.
        val v = verification(loaded = true, inferenceOk = true, checkVersion = 2)
        assertEquals(CandidateTier.LOADABLE, v.tier())
        assertEquals(emptyList(), v.passed)
    }

    @Test
    fun answering_without_any_capability_passing_is_loadable() {
        val v = verification(
            loaded = true, inferenceOk = false,
            checks = mapOf(VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.FAIL), VerifiedCapability.TRANSLATION to CapabilityCheck(CheckStatus.FAIL)),
        )
        assertEquals(CandidateTier.LOADABLE, v.tier())
    }

    @Test
    fun an_answer_judged_by_an_older_check_is_not_trusted_as_functional() {
        assertEquals(CandidateTier.LOADABLE, verification(loaded = true, inferenceOk = true, checkVersion = 1).tier())
        assertEquals(CandidateTier.LOADABLE, verification(loaded = false, inferenceOk = true, checkVersion = 1).tier())
    }

    @Test
    fun a_record_written_before_check_versions_existed_reads_as_the_first_check() {
        val old = kotlinx.serialization.json.Json.decodeFromString(
            DeviceVerification.serializer(),
            """{"deviceProfile":"p","runtimeId":"llama_cpp","loaded":true,"inferenceOk":true,"verifiedAtEpochMs":1}""",
        )
        assertEquals(1, old.checkVersion)
        assertEquals(CandidateTier.LOADABLE, old.tier())
    }

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
