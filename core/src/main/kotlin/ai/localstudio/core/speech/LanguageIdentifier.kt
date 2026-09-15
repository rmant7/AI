package ai.localstudio.core.speech

/**
 * A window of audio handed to a [LanguageIdentifier] — deliberately not the
 * same shape [ai.localstudio.core.audio.AudioSource] streams (short,
 * transport-sized `ShortArray` chunks, ~0.5-1s each). LID wants a larger,
 * overlapping rolling window (~2-5s, see [DefaultStreamingSpeechRouter]'s
 * own doc comment for why), assembled from several transport chunks by
 * whoever drives the identifier — the router, not the audio source.
 */
data class AudioChunk(
    val pcm: ShortArray,
    val sampleRate: Int,
    val startMs: Long,
)

data class LanguageProbability(val language: Language, val probability: Float)

/**
 * Richer than a bare language guess on purpose: [alternatives] and
 * [isMixed] are what let the router tell "confidently one language" apart
 * from "plausibly two" — a distinction a single [Language] value can never
 * carry, and exactly the case (code-switched speech) this whole routing
 * layer exists to handle instead of silently mis-detecting.
 */
data class LanguageIdResult(
    val language: Language,
    val confidence: Float,
    val alternatives: List<LanguageProbability> = emptyList(),
    val isMixed: Boolean = false,
)

/**
 * Identifies the spoken language of a window of audio. Implementations are
 * swappable and the router never knows which one is in play — a cheap
 * heuristic, a dedicated LID model, or (initially, since it's already
 * available) a multilingual ASR model's own language-detection output are
 * all just a [LanguageIdentifier] to everything above this interface.
 */
fun interface LanguageIdentifier {
    suspend fun identify(audio: AudioChunk): LanguageIdResult
}
