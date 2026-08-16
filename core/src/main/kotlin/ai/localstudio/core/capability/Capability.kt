package ai.localstudio.core.capability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a model can do, independent of its name, vendor or runtime.
 *
 * The whole system is addressed through capabilities: the router asks for
 * [SPEECH_TO_TEXT], not for "Whisper", so replacing a model never touches
 * anything above the registry.
 */
@Serializable
enum class Capability(val id: String) {
    @SerialName("speech_to_text")
    SPEECH_TO_TEXT("speech_to_text"),

    @SerialName("speaker_diarization")
    SPEAKER_DIARIZATION("speaker_diarization"),

    @SerialName("text_generation")
    TEXT_GENERATION("text_generation"),

    @SerialName("reasoning")
    REASONING("reasoning"),

    @SerialName("coding")
    CODING("coding"),

    @SerialName("translation")
    TRANSLATION("translation"),

    @SerialName("vision")
    VISION("vision"),

    @SerialName("ocr")
    OCR("ocr"),

    @SerialName("image_understanding")
    IMAGE_UNDERSTANDING("image_understanding"),

    @SerialName("video_understanding")
    VIDEO_UNDERSTANDING("video_understanding"),

    @SerialName("embedding")
    EMBEDDING("embedding"),

    @SerialName("reranking")
    RERANKING("reranking");

    companion object {
        private val byId = entries.associateBy(Capability::id)

        fun fromId(id: String): Capability =
            byId[id] ?: throw IllegalArgumentException("Unknown capability: $id")
    }
}
