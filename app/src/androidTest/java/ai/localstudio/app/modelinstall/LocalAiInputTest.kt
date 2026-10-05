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

    @Test
    fun only_a_current_check_is_reported_and_untested_stays_untested() {
        val current = DeviceVerification(
            deviceProfile = "Pixel", runtimeId = "llama_cpp", loaded = true, inferenceOk = true, verifiedAtEpochMs = 1,
            checkVersion = DeviceVerification.CURRENT_CHECK,
            checks = mapOf(VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.PASS), VerifiedCapability.VISION to CapabilityCheck(CheckStatus.FAIL, "wrong")),
        )
        assertEquals(
            mapOf(LocalCapability.TEXT to CheckResult.PASS, LocalCapability.TRANSLATION to CheckResult.NOT_TESTED, LocalCapability.VISION to CheckResult.FAIL),
            SdkMapping.checks(current),
        )
        assertEquals(emptyMap<LocalCapability, CheckResult>(), SdkMapping.checks(current.copy(checkVersion = 1)))
    }
}
