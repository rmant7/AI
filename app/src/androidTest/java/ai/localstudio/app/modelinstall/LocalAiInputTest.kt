package ai.localstudio.app.modelinstall

import ai.localstudio.app.localai.SdkMapping
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.model.install.CapabilityCheck
import ai.localstudio.model.install.CheckStatus
import ai.localstudio.model.install.DeviceVerification
import ai.localstudio.model.install.VerifiedCapability
import ai.localstudio.sdk.CheckResult
import ai.localstudio.sdk.GenerationOptions
import ai.localstudio.sdk.LocalAiInput
import ai.localstudio.sdk.LocalCapability
import ai.localstudio.sdk.LocalImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SdkMapping]: images stay a collection in order, and what a model can take is decided by what it has installed. */
class LocalAiInputTest {

    private val text = RuntimeBinding(RuntimeKind.LLAMA_CPP, "/m/model.gguf", 1_000)
    private val seeing = text.copy(mmprojArtifact = "/m/projector.gguf")
    private val two = listOf(LocalImage(byteArrayOf(1, 2), "image/png"), LocalImage(byteArrayOf(3), "image/jpeg"))

    @Test
    fun text_runs_anywhere_and_images_need_the_projector_on_disk() {
        assertTrue(SdkMapping.canTake(text, LocalAiInput("hi")))
        assertFalse(SdkMapping.canTake(text, LocalAiInput("what is this?", two)))
        assertTrue(SdkMapping.canTake(seeing, LocalAiInput("what is this?", two)))
        assertFalse(LocalCapability.VISION in SdkMapping.capabilities(text))
        assertTrue(LocalCapability.VISION in SdkMapping.capabilities(seeing))
    }

    @Test
    fun every_image_reaches_the_request_in_order_as_a_data_uri() {
        val request = SdkMapping.request(LocalAiInput("compare these", two, systemPrompt = "be brief"), GenerationOptions(maxTokens = 64, temperature = 0.0))
        assertEquals(listOf("data:image/png;base64,AQI=", "data:image/jpeg;base64,Aw=="), request.images.map { it.uri })
        assertEquals("compare these", request.prompt)
        assertEquals("be brief", request.systemPrompt)
        assertEquals(64, request.maxTokens)
    }

    private val artifact = ai.localstudio.model.install.ArtifactId("acme/see-GGUF", "a".repeat(40), "see-Q4_K_M.gguf", "mmproj-F16.gguf")
    private val here = ai.localstudio.model.install.VerificationContext(artifact, "Google Pixel 10 Pro", "llama.cpp b10448 / jni 2")

    private val checked = DeviceVerification(
        deviceProfile = "Pixel", runtimeId = "llama_cpp", loaded = true, inferenceOk = true, verifiedAtEpochMs = 1,
        checkVersion = DeviceVerification.CURRENT_CHECK,
        checks = mapOf(VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.PASS), VerifiedCapability.VISION to CapabilityCheck(CheckStatus.FAIL, "wrong")),
        artifact = artifact, device = here.device, runtimeVersion = here.runtimeVersion,
    )

    @Test
    fun a_valid_check_is_reported_as_recorded_and_untested_stays_untested() {
        assertEquals(
            mapOf(LocalCapability.TEXT to CheckResult.PASS, LocalCapability.TRANSLATION to CheckResult.NOT_TESTED, LocalCapability.VISION to CheckResult.FAIL),
            SdkMapping.checks(checked, here),
        )
        assertEquals(emptyMap<LocalCapability, CheckResult>(), SdkMapping.checks(null, here))
    }

    @Test
    fun a_check_under_any_other_context_is_stale_never_dropped_and_never_a_pass() {
        val stale = mapOf(LocalCapability.TEXT to CheckResult.STALE, LocalCapability.TRANSLATION to CheckResult.NOT_TESTED, LocalCapability.VISION to CheckResult.STALE)
        assertEquals("other projector", stale, SdkMapping.checks(checked, here.copy(artifact = artifact.copy(projectorFile = "mmproj-Q8_0.gguf"))))
        assertEquals("other main file", stale, SdkMapping.checks(checked, here.copy(artifact = artifact.copy(mainFile = "see-Q8_0.gguf"))))
        assertEquals("other device", stale, SdkMapping.checks(checked, here.copy(device = "samsung SM-G781B")))
        assertEquals("other llama.cpp", stale, SdkMapping.checks(checked, here.copy(runtimeVersion = "llama.cpp b11000 / jni 2")))
        assertEquals("other questions", stale, SdkMapping.checks(checked, here.copy(checkVersion = 99)))
        assertEquals("bytes unknown", stale, SdkMapping.checks(checked, null))
        assertEquals("recorded before contexts", stale, SdkMapping.checks(checked.copy(artifact = null), here))
    }

    @Test
    fun a_candidate_is_offered_by_its_parts_and_named_by_its_artifact() {
        val candidate = DiscoveredCandidate(
            repoId = "acme/see-GGUF", fileName = "see-Q4_K_M.gguf", filePath = "see-Q4_K_M.gguf", sizeBytes = 2_000,
            architecture = "gemma3", contextLength = 4096, notes = emptyList(), commit = "a".repeat(40), downloads = 1,
            projector = ai.localstudio.model.install.ProjectorFile(ai.localstudio.model.install.ModelFile("acme/see-GGUF", "a".repeat(40), "mmproj-F16.gguf", 800), "gemma3"),
            verification = checked,
        )
        val sdk = SdkMapping.candidate(candidate, installed = true, now = here)
        assertEquals(artifact.key, sdk.id)
        assertEquals(sdk.id, sdk.artifact.key)
        assertEquals(2_800, sdk.sizeBytes)
        assertTrue(LocalCapability.VISION in sdk.capabilities)
        assertEquals(CheckResult.FAIL, sdk.verified[LocalCapability.VISION])
    }
}
