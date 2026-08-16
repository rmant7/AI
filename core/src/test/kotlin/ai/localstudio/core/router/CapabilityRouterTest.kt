package ai.localstudio.core.router

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.pipeline.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityRouterTest {

    private val router = CapabilityRouter()

    @Test
    fun `audio input always starts with transcription`() {
        val plan = router.route(RequestSignals(hasAudio = true))

        assertTrue(Capability.SPEECH_TO_TEXT in plan.capabilities)
        assertEquals(NodeType.SPEECH_TO_TEXT, plan.stages.first())
        assertEquals(NodeType.RESPONSE, plan.stages.last())
    }

    @Test
    fun `an image routes through vision before generation`() {
        val plan = router.route(RequestSignals(text = "что здесь написано?", hasImage = true))

        assertTrue(Capability.IMAGE_UNDERSTANDING in plan.capabilities)
        assertTrue(plan.stages.indexOf(NodeType.VISION_ANALYZE) < plan.stages.indexOf(NodeType.TEXT_GENERATION))
    }

    @Test
    fun `video fans out into frames and audio`() {
        val plan = router.route(RequestSignals(hasVideo = true))

        assertTrue(plan.stages.containsAll(listOf(NodeType.FRAME_EXTRACT, NodeType.SPEECH_TO_TEXT, NodeType.VISION_ANALYZE)))
        assertTrue(Capability.VIDEO_UNDERSTANDING in plan.capabilities)
    }

    @Test
    fun `a reference to earlier work triggers memory retrieval`() {
        val plan = router.route(RequestSignals(text = "Продолжи то, что мы делали вчера"))

        assertTrue(NodeType.MEMORY_SEARCH in plan.stages)
        assertTrue(plan.explanation.any { "memory" in it })
    }

    @Test
    fun `memory retrieval is skipped when memory is off`() {
        val plan = router.route(RequestSignals(text = "продолжи вчерашнее", memoryEnabled = false))

        assertFalse(NodeType.MEMORY_SEARCH in plan.stages)
    }

    @Test
    fun `documents in scope add retrieval capabilities`() {
        val plan = router.route(RequestSignals(text = "найди в моих документах проект X", hasDocumentContext = true))

        assertTrue(NodeType.KNOWLEDGE_SEARCH in plan.stages)
        assertTrue(plan.capabilities.containsAll(listOf(Capability.EMBEDDING, Capability.RERANKING)))
    }

    @Test
    fun `a coding request asks for the coding capability`() {
        val plan = router.route(RequestSignals(text = "Проанализируй этот код и найди баг"))

        assertTrue(Capability.CODING in plan.capabilities)
        assertFalse(Capability.TRANSLATION in plan.capabilities)
    }

    @Test
    fun `a translation request asks for the translation capability`() {
        val plan = router.route(RequestSignals(text = "Переведи это на английский"))

        assertTrue(Capability.TRANSLATION in plan.capabilities)
    }

    @Test
    fun `plain text still ends in generation`() {
        val plan = router.route(RequestSignals(text = "привет"))

        assertEquals(listOf(Capability.TEXT_GENERATION), plan.capabilities)
        assertEquals(listOf(NodeType.CONTEXT_BUILD, NodeType.TEXT_GENERATION, NodeType.RESPONSE), plan.stages)
    }
}
