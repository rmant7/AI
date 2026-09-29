package ai.localstudio.app.voicebenchmark

import java.io.File

/**
 * Chatterbox Multilingual (zero-shot voice cloning, 23 languages including
 * Russian and Hebrew). Placeholder like [Qwen3TtsBenchmarkEngine]: no local
 * implementation exists in this app yet, so it reports
 * [EngineAvailability.NOT_INSTALLED] and never fabricates output.
 */
class ChatterboxBenchmarkEngine : VoiceBenchmarkEngine {

    override val id = "chatterbox_multilingual"
    override val displayName = "Chatterbox Multilingual"
    override val supportedLanguages = setOf("ru", "en", "he")

    override suspend fun availability() = EngineAvailability.NOT_INSTALLED

    override suspend fun synthesize(
        text: String,
        language: String,
        referenceAudio: File?,
        referenceText: String?,
    ) = VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.NOT_INSTALLED)
}
