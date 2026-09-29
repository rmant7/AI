package ai.localstudio.app.voicebenchmark

import ai.localstudio.whisper.CpuVariant
import ai.localstudio.qwen3tts.Qwen3TtsNative
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Qwen3-TTS Base voice cloning. The 0.6B model runs locally through
 * qwen3-tts.cpp (see :qwen3tts); the 1.7B is still a placeholder that reports
 * [EngineAvailability.NOT_INSTALLED] and never produces audio.
 *
 * Qwen3-TTS does not list Hebrew among its languages, so it is left out of
 * [supportedLanguages] and shows up as an unsupported-language result.
 *
 * A run measures the three costs separately: model load (first run only, then
 * the model stays resident until [QwenTtsRuntimeManager.release]), voice
 * preparation (once per reference recording + transcript) and generation —
 * generation time and RTF cover neither of the other two.
 */
class Qwen3TtsBenchmarkEngine(
    context: Context,
    private val size: Size,
    private val outputDir: File,
) : VoiceBenchmarkEngine {

    enum class Size(val label: String) { SMALL("0.6B"), LARGE("1.7B") }

    private val appContext = context.applicationContext

    override val id = "qwen3_tts_${size.label.lowercase()}"
    override val displayName = "Qwen3-TTS ${size.label} Base"
    override val supportedLanguages = setOf("ru", "en")

    override suspend fun availability(): EngineAvailability = when {
        size == Size.LARGE -> EngineAvailability.NOT_INSTALLED
        !Qwen3TtsNative.isLoaded -> EngineAvailability.NOT_INSTALLED
        // The native build uses dotprod/fp16 instructions; on a CPU without
        // them loading it would crash the process with SIGILL.
        CpuVariant.current == CpuVariant.BASELINE -> EngineAvailability.UNSUPPORTED_DEVICE
        !QwenTtsModelProvider.get(appContext).isReady() -> EngineAvailability.MODEL_NOT_DOWNLOADED
        else -> EngineAvailability.AVAILABLE
    }

    override suspend fun synthesize(
        text: String,
        language: String,
        referenceAudio: File?,
        referenceText: String?,
    ): VoiceBenchmarkResult {
        val languageId = LANGUAGE_IDS[language]
            ?: return VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.UNSUPPORTED_LANGUAGE)
        if (referenceAudio == null || referenceText.isNullOrBlank()) {
            return VoiceBenchmarkResult.failed(
                id, VoiceBenchmarkStatus.ERROR, "Voice cloning needs a reference recording and its transcript",
            )
        }
        outputDir.mkdirs()
        val output = File(outputDir, "qwen3_tts_${size.label}_${System.currentTimeMillis()}.wav")
        return try {
            val load = QwenTtsRuntimeManager.ensureLoaded(appContext, QwenTtsModelProvider.get(appContext))
            val prep = QwenTtsRuntimeManager.prepareVoice(appContext, referenceAudio, referenceText)
            val generation = QwenTtsRuntimeManager.synthesize(text, languageId, output)
            val audioMs = WavFiles.durationMs(output)
            if (audioMs == null || audioMs <= 0) {
                output.delete()
                VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.ERROR, "Qwen3-TTS produced no audio")
            } else {
                VoiceBenchmarkResult.ok(id, output, generation.generationMs, audioMs, load.loadMs.takeUnless { load.alreadyLoaded })
                    .copy(
                        voicePrepMs = prep.prepMs,
                        firstAudioMs = generation.firstAudioMs,
                        memoryBeforeMb = load.pssMbBefore.takeUnless { load.alreadyLoaded },
                        memoryAfterMb = load.pssMbAfter,
                        availRamMb = load.availRamMbBefore,
                    )
            }
        } catch (e: CancellationException) {
            // Cancel must not leave the native model resident.
            output.delete()
            withContext(NonCancellable) { QwenTtsRuntimeManager.release() }
            throw e
        } catch (e: QwenTtsException) {
            output.delete()
            VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.ERROR, e.message)
        }
    }

    private companion object {
        // Codec language ids from qwen3-tts.cpp (qwen3_tts.h).
        val LANGUAGE_IDS = mapOf("en" to 2050, "ru" to 2069)
    }
}
