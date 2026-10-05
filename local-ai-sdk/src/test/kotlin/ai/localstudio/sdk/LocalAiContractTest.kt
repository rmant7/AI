package ai.localstudio.sdk

import ai.localstudio.sdk.testing.FakeLocalAi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAiContractTest {

    private val textModel = LocalModel("qwen", "Qwen", setOf(LocalCapability.TEXT), mapOf(LocalCapability.TEXT to CheckResult.PASS), 2_000_000_000)
    private val visionModel = LocalModel(
        "gemma", "Gemma", setOf(LocalCapability.TEXT, LocalCapability.VISION),
        mapOf(LocalCapability.TEXT to CheckResult.PASS, LocalCapability.VISION to CheckResult.NOT_TESTED), 3_000_000_000,
    )
    private val png = LocalImage(byteArrayOf(1, 2, 3), "image/png")

    @Test
    fun `images make vision required and are kept as a collection in order`() {
        val second = LocalImage(byteArrayOf(4), "image/jpeg")
        val input = LocalAiInput("compare", listOf(png, second))
        assertEquals(setOf(LocalCapability.TEXT, LocalCapability.VISION), input.requiredCapabilities)
        assertEquals(listOf(png, second), input.images)
        assertEquals(setOf(LocalCapability.TEXT), LocalAiInput("hi").requiredCapabilities)
    }

    @Test
    fun `an image is copied in and out, never shared with the caller`() {
        val bytes = byteArrayOf(1, 2, 3)
        val image = LocalImage(bytes, "image/png")
        bytes[0] = 9
        image.bytes[1] = 9
        assertEquals(LocalImage(byteArrayOf(1, 2, 3), "image/png"), image)
    }

    @Test
    fun `offered is not proven`() {
        assertTrue(LocalCapability.VISION in visionModel.capabilities)
        assertFalse(visionModel.proven(LocalCapability.VISION))
        assertTrue(visionModel.proven(LocalCapability.TEXT))
    }

    @Test
    fun `images to a model that cannot see fail as unseen, not as an answer`() = runBlocking {
        val ai = FakeLocalAi(listOf(textModel, visionModel), chatModelId = "qwen")
        assertFailsWith<LocalAiException.ImageNotSeen> { ai.generate(LocalAiInput("what is this?", listOf(png))).toList() }
        assertEquals(listOf("echo: what is this?"), ai.generate(LocalAiInput("what is this?", listOf(png)), modelId = "gemma").toList())
        assertEquals(listOf("gemma"), ai.received.map { it.first })
    }

    @Test
    fun `an unknown model and a missing capability are told apart`() = runBlocking {
        val ai = FakeLocalAi(listOf(textModel))
        assertFailsWith<LocalAiException.UnknownModel> { ai.generate(LocalAiInput("hi"), modelId = "nope").toList() }
        val missing = assertFailsWith<LocalAiException.NoModel> {
            ai.translate(TranslationRequest("hi", Language("en", "English"), Language("fr", "French")))
        }
        assertEquals(LocalCapability.TRANSLATION, missing.capability)
    }

    @Test
    fun `options reject what no model can do`() {
        assertFailsWith<IllegalArgumentException> { GenerationOptions(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { GenerationOptions(temperature = -1.0) }
    }
}
