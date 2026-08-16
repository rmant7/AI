package ai.localstudio.core.engine

import ai.localstudio.core.pipeline.EdgeSpec
import ai.localstudio.core.pipeline.NodeSpec
import ai.localstudio.core.pipeline.NodeType
import ai.localstudio.core.pipeline.PipelineSpec
import ai.localstudio.core.router.RequestSignals
import ai.localstudio.core.router.RoutePlan

/**
 * Materialises a route into a runnable graph.
 *
 * This is what makes the router and the pipeline engine the same mechanism
 * rather than two parallel ones: an ordinary chat request becomes an ad-hoc
 * pipeline built here, while a user-authored pipeline is the same structure
 * loaded from JSON. Both then run through the same validator and executor, so
 * there is no second code path that can behave differently.
 *
 * The shape mirrors `pipelines/voice_rag.json`: input is preprocessed in a
 * chain, every retrieval stage hangs off the preprocessed input in parallel,
 * and all of them converge on context assembly.
 */
object PipelineBuilder {

    fun fromRoute(
        plan: RoutePlan,
        signals: RequestSignals,
        id: String = "adhoc",
        writeMemory: Boolean = signals.memoryEnabled,
    ): PipelineSpec {
        val nodes = mutableListOf<NodeSpec>()
        val edges = mutableListOf<EdgeSpec>()

        val sourceType = sourceFor(signals)
        nodes += NodeSpec(SOURCE_ID, sourceType)

        // Stages that transform the input itself, in order, forming a chain.
        var upstream = SOURCE_ID
        for (stage in plan.stages.filter { it in PREPROCESSING }) {
            val nodeId = stage.id
            nodes += NodeSpec(nodeId, stage)
            edges += EdgeSpec(upstream, nodeId)
            upstream = nodeId
        }

        nodes += NodeSpec(CONTEXT_ID, NodeType.CONTEXT_BUILD)
        edges += EdgeSpec(upstream, CONTEXT_ID)

        // Retrieval stages run against the preprocessed input, in parallel.
        for (stage in plan.stages.filter { it in RETRIEVAL }) {
            val nodeId = stage.id
            nodes += NodeSpec(nodeId, stage)
            edges += EdgeSpec(upstream, nodeId)
            edges += EdgeSpec(nodeId, CONTEXT_ID)
        }

        nodes += NodeSpec(GENERATION_ID, NodeType.TEXT_GENERATION)
        edges += EdgeSpec(CONTEXT_ID, GENERATION_ID)

        if (writeMemory) {
            nodes += NodeSpec(MEMORY_UPDATE_ID, NodeType.MEMORY_UPDATE)
            edges += EdgeSpec(GENERATION_ID, MEMORY_UPDATE_ID)
        }

        nodes += NodeSpec(RESPONSE_ID, NodeType.RESPONSE)
        edges += EdgeSpec(GENERATION_ID, RESPONSE_ID)

        return PipelineSpec(
            id = id,
            name = plan.capabilities.joinToString(" + ") { it.id },
            description = plan.explanation.joinToString("; ").ifEmpty { null },
            nodes = nodes,
            edges = edges,
        )
    }

    private fun sourceFor(signals: RequestSignals): NodeType = when {
        signals.hasVideo -> NodeType.VIDEO_INPUT
        signals.hasAudio -> NodeType.MICROPHONE
        signals.hasImage -> NodeType.IMAGE_INPUT
        else -> NodeType.TEXT_INPUT
    }

    const val SOURCE_ID = "input"
    const val CONTEXT_ID = "context"
    const val GENERATION_ID = "generation"
    const val MEMORY_UPDATE_ID = "memory_update"
    const val RESPONSE_ID = "response"

    private val PREPROCESSING = setOf(
        NodeType.VAD,
        NodeType.FRAME_EXTRACT,
        NodeType.SPEECH_TO_TEXT,
        NodeType.DIARIZATION,
        NodeType.VISION_ANALYZE,
        NodeType.OCR,
    )

    private val RETRIEVAL = setOf(
        NodeType.MEMORY_SEARCH,
        NodeType.KNOWLEDGE_SEARCH,
    )
}
