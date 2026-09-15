package ai.localstudio.core.model

import ai.localstudio.core.speech.Language

/** A reference to audio, never the samples themselves — buffers do not belong in a graph. */
data class AudioRef(
    val uri: String,
    val durationMs: Long? = null,
    val sampleRate: Int? = null,
)

data class ImageRef(
    val uri: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
)

data class VideoRef(
    val uri: String,
    val durationMs: Long? = null,
)

data class DocumentRef(
    val uri: String,
    val mimeType: String? = null,
    val title: String? = null,
)

data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val speaker: String? = null,
    val confidence: Double? = null,
    /** Set by a [ai.localstudio.core.speech.StreamingSpeechRouter] session — null for every other producer (existing Whisper/Vosk sessions, file transcription). Diagnostic: `[RU][vosk-ru] …` — see docs/15-speech-routing.md. */
    val language: Language? = null,
    /** The [ai.localstudio.core.speech.SpeechModelInfo.id] that produced this segment — same routing-only, diagnostic-only field as [language]. */
    val modelId: String? = null,
)

/**
 * Output of any speech provider. Downstream stages consume this shape, so
 * swapping whisper.cpp for another ASR model changes nothing above it.
 */
data class Transcript(
    val text: String,
    val language: String? = null,
    val confidence: Double? = null,
    val segments: List<TranscriptSegment> = emptyList(),
) {
    val speakers: Set<String>
        get() = segments.mapNotNull { it.speaker }.toSet()
}

data class VisionResult(
    val description: String,
    val ocrText: String? = null,
    val tags: List<String> = emptyList(),
    val confidence: Double? = null,
)
