package ai.localstudio.core.engine

import ai.localstudio.core.pipeline.NodeType
import ai.localstudio.core.pipeline.PipelineValidator
import ai.localstudio.core.router.CapabilityRouter
import ai.localstudio.core.router.RequestSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineBuilderTest {

    private val router = CapabilityRouter()

    private fun build(signals: RequestSignals) =
        PipelineBuilder.fromRoute(router.route(signals), signals)

    @Test
    fun `every built pipeline is valid by construction`() {
        val cases = listOf(
            RequestSignals(text = "привет"),
            RequestSignals(hasAudio = true),
            RequestSignals(text = "что здесь?", hasImage = true),
            RequestSignals(hasVideo = true),
            RequestSignals(text = "продолжи вчерашнее", hasDocumentContext = true, knowledgeEnabled = true),
        )

        for (signals in cases) {
            val spec = build(signals)
            assertEquals(emptyList(), PipelineValidator.validate(spec), "invalid for $signals")
        }
    }

    @Test
    fun `a typed request is the minimal chain`() {
        val spec = build(RequestSignals(text = "привет"))

        assertEquals(
            listOf(NodeType.TEXT_INPUT, NodeType.CONTEXT_BUILD, NodeType.TEXT_GENERATION, NodeType.MEMORY_UPDATE, NodeType.RESPONSE),
            spec.nodes.map { it.type },
        )
    }

    @Test
    fun `audio is transcribed before anything else consumes it`() {
        val spec = build(RequestSignals(hasAudio = true))
        val order = PipelineValidator.topologicalOrder(spec)!!

        assertEquals(NodeType.MICROPHONE, spec.nodes.first().type)
        assertTrue(order.indexOf(NodeType.SPEECH_TO_TEXT.id) < order.indexOf(PipelineBuilder.CONTEXT_ID))
    }

    @Test
    fun `retrieval stages hang off the preprocessed input in parallel`() {
        val signals = RequestSignals(
            text = "что мы решили вчера про этот документ",
            hasAudio = true,
            hasDocumentContext = true,
        )
        val spec = build(signals)

        val memoryInputs = spec.incoming(NodeType.MEMORY_SEARCH.id)
        val knowledgeInputs = spec.incoming(NodeType.KNOWLEDGE_SEARCH.id)

        assertEquals(listOf(NodeType.SPEECH_TO_TEXT.id), memoryInputs)
        assertEquals(listOf(NodeType.SPEECH_TO_TEXT.id), knowledgeInputs)
        assertTrue(spec.outgoing(NodeType.MEMORY_SEARCH.id).contains(PipelineBuilder.CONTEXT_ID))
        assertTrue(spec.outgoing(NodeType.KNOWLEDGE_SEARCH.id).contains(PipelineBuilder.CONTEXT_ID))
    }

    @Test
    fun `memory writing is omitted when memory is off`() {
        val withMemory = build(RequestSignals(text = "привет", memoryEnabled = true))
        val without = build(RequestSignals(text = "привет", memoryEnabled = false))

        assertTrue(NodeType.MEMORY_UPDATE in withMemory.nodes.map { it.type })
        assertTrue(NodeType.MEMORY_UPDATE !in without.nodes.map { it.type })
    }

    @Test
    fun `the built pipeline declares the capabilities it needs`() {
        val spec = build(RequestSignals(hasAudio = true, hasDocumentContext = true, knowledgeEnabled = true))

        assertTrue(spec.requiredCapabilities().containsAll(
            listOf(
                ai.localstudio.core.capability.Capability.SPEECH_TO_TEXT,
                ai.localstudio.core.capability.Capability.EMBEDDING,
                ai.localstudio.core.capability.Capability.TEXT_GENERATION,
            ),
        ))
    }

    @Test
    fun `video fans out into frames, speech and vision before context`() {
        val spec = build(RequestSignals(hasVideo = true))
        val order = PipelineValidator.topologicalOrder(spec)!!
        val types = spec.nodes.map { it.type }

        assertTrue(types.containsAll(listOf(NodeType.FRAME_EXTRACT, NodeType.SPEECH_TO_TEXT, NodeType.VISION_ANALYZE)))
        assertTrue(order.indexOf(NodeType.VISION_ANALYZE.id) < order.indexOf(PipelineBuilder.CONTEXT_ID))
    }
}
