package ai.localstudio.app.modelinstall

import ai.localstudio.app.localai.LocalAiInput
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LocalAiInput]: images are a collection, kept in order, and what a model can take is decided by what it has installed. */
class LocalAiInputTest {

    private val text = RuntimeBinding(RuntimeKind.LLAMA_CPP, "/m/model.gguf", 1_000)
    private val seeing = text.copy(mmprojArtifact = "/m/projector.gguf")
    private val two = listOf(ImageRef("data:image/png;base64,AA"), ImageRef("data:image/png;base64,BB"))

    @Test
    fun text_runs_anywhere_and_images_need_the_projector_on_disk() {
        assertTrue(LocalAiInput("hi").canRunOn(text))
        assertFalse(LocalAiInput("what is this?", two).canRunOn(text))
        assertTrue(LocalAiInput("what is this?", two).canRunOn(seeing))
        assertEquals(setOf(Capability.TEXT_GENERATION), LocalAiInput("hi").requiredCapabilities)
        assertEquals(setOf(Capability.TEXT_GENERATION, Capability.VISION), LocalAiInput("x", two).requiredCapabilities)
    }

    @Test
    fun every_image_reaches_the_request_in_order() {
        val request = LocalAiInput("compare these", two, systemPrompt = "be brief").toRequest(maxTokens = 64, temperature = 0.0, repeatPenalty = 1.0)
        assertEquals(two, request.images)
        assertEquals("compare these", request.prompt)
        assertEquals("be brief", request.systemPrompt)
        assertEquals(64, request.maxTokens)
    }
}
