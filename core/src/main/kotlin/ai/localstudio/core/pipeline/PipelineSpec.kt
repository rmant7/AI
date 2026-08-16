package ai.localstudio.core.pipeline

import ai.localstudio.core.capability.Capability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The vocabulary of the pipeline editor. Node types are stages, not models:
 * a [SPEECH_TO_TEXT] node asks for the capability and the router picks the
 * model, so a pipeline saved today keeps working after the models change.
 */
@Serializable
enum class NodeType(val id: String, val requires: Capability? = null, val isSource: Boolean = false) {
    @SerialName("microphone")
    MICROPHONE("microphone", isSource = true),

    @SerialName("audio_input")
    AUDIO_INPUT("audio_input", isSource = true),

    @SerialName("camera")
    CAMERA("camera", isSource = true),

    @SerialName("image_input")
    IMAGE_INPUT("image_input", isSource = true),

    @SerialName("video_input")
    VIDEO_INPUT("video_input", isSource = true),

    @SerialName("document_input")
    DOCUMENT_INPUT("document_input", isSource = true),

    @SerialName("text_input")
    TEXT_INPUT("text_input", isSource = true),

    @SerialName("vad")
    VAD("vad"),

    @SerialName("speech_to_text")
    SPEECH_TO_TEXT("speech_to_text", Capability.SPEECH_TO_TEXT),

    @SerialName("diarization")
    DIARIZATION("diarization", Capability.SPEAKER_DIARIZATION),

    @SerialName("frame_extract")
    FRAME_EXTRACT("frame_extract"),

    @SerialName("vision_analyze")
    VISION_ANALYZE("vision_analyze", Capability.IMAGE_UNDERSTANDING),

    @SerialName("ocr")
    OCR("ocr", Capability.OCR),

    @SerialName("memory_search")
    MEMORY_SEARCH("memory_search"),

    @SerialName("knowledge_search")
    KNOWLEDGE_SEARCH("knowledge_search", Capability.EMBEDDING),

    @SerialName("context_build")
    CONTEXT_BUILD("context_build"),

    @SerialName("text_generation")
    TEXT_GENERATION("text_generation", Capability.TEXT_GENERATION),

    @SerialName("memory_update")
    MEMORY_UPDATE("memory_update"),

    @SerialName("response")
    RESPONSE("response");

    companion object {
        private val byId = entries.associateBy(NodeType::id)

        fun fromId(id: String): NodeType =
            byId[id] ?: throw IllegalArgumentException("Unknown node type: $id")
    }
}

@Serializable
data class NodeSpec(
    val id: String,
    val type: NodeType,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class EdgeSpec(val from: String, val to: String)

/**
 * A user-editable graph. Small enough to hand-write, serialise to SQLite and
 * ship between devices; no workflow engine is pulled in for this.
 */
@Serializable
data class PipelineSpec(
    val id: String,
    val name: String,
    val description: String? = null,
    val nodes: List<NodeSpec>,
    val edges: List<EdgeSpec> = emptyList(),
) {
    fun node(id: String): NodeSpec? = nodes.firstOrNull { it.id == id }

    fun incoming(nodeId: String): List<String> = edges.filter { it.to == nodeId }.map { it.from }

    fun outgoing(nodeId: String): List<String> = edges.filter { it.from == nodeId }.map { it.to }

    /** Capabilities that must be resolvable before this pipeline can run. */
    fun requiredCapabilities(): Set<Capability> = nodes.mapNotNull { it.type.requires }.toSet()
}
