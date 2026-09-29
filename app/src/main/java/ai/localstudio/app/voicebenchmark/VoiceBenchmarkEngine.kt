package ai.localstudio.app.voicebenchmark

import java.io.File

enum class EngineAvailability { AVAILABLE, NOT_INSTALLED, MODEL_NOT_DOWNLOADED, UNSUPPORTED_DEVICE }

/**
 * One text-to-speech backend as the Voice Benchmark screen sees it. The
 * screen and [VoiceBenchmarkRunner] know nothing about how a WAV is produced
 * — an on-device model, the system TTS, anything — so the same backend can
 * later be reused for the avatar's voice without touching them.
 *
 * Every engine in one run receives exactly the same text, language and
 * reference voice.
 */
interface VoiceBenchmarkEngine {
    val id: String
    val displayName: String

    /** BCP-47 language codes ("ru", "en", "he") this engine can speak. */
    val supportedLanguages: Set<String>

    /** Anything other than [EngineAvailability.AVAILABLE] says why the backend cannot run right now. */
    suspend fun availability(): EngineAvailability

    /**
     * [referenceAudio] is a mono 24 kHz 16-bit WAV of the voice to imitate
     * and [referenceText] its transcript; both are null/blank for engines
     * that cannot clone a voice. Must be cancellable, and must report
     * failure in the returned result rather than by throwing.
     */
    suspend fun synthesize(
        text: String,
        language: String,
        referenceAudio: File?,
        referenceText: String?,
    ): VoiceBenchmarkResult
}
