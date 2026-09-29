package ai.localstudio.app.voicebenchmark

import java.io.File

/**
 * Qwen3-TTS Base (voice cloning from a short reference recording), 0.6B or
 * 1.7B. Placeholder: no native Qwen runtime is wired into this app yet, so
 * this always reports [EngineAvailability.NOT_INSTALLED] and never produces
 * audio. It exists so the screen, runner and result cards already handle
 * the engine; the real backend replaces [synthesize] in a later step.
 *
 * Qwen3-TTS does not list Hebrew among its supported languages — it is
 * deliberately left out of [supportedLanguages], so the benchmark shows
 * "unsupported language" for it instead of hiding the comparison.
 */
class Qwen3TtsBenchmarkEngine(private val size: Size) : VoiceBenchmarkEngine {

    enum class Size(val label: String) { SMALL("0.6B"), LARGE("1.7B") }

    override val id = "qwen3_tts_${size.label.lowercase()}"
    override val displayName = "Qwen3-TTS ${size.label} Base"
    override val supportedLanguages = setOf("ru", "en")

    override suspend fun availability() = EngineAvailability.NOT_INSTALLED

    override suspend fun synthesize(
        text: String,
        language: String,
        referenceAudio: File?,
        referenceText: String?,
    ) = VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.NOT_INSTALLED)
}
