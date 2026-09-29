package ai.localstudio.app.voicebenchmark

import ai.localstudio.app.AppContainer
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
        val sections = StringBuilder()
        fun section(title: String, body: String) {
            if (body.isBlank()) return
            sections.append("\n--- ").append(title).append(" ---\n").append(body.trimEnd()).append('\n')
        }
        record("run started: ${text.length} chars, language=$language, reference=${referenceAudio.length() / 1024} KB")
        return try {
            val load = QwenTtsRuntimeManager.ensureLoaded(appContext, QwenTtsModelProvider.get(appContext))
            section("native: model load", load.log)
            record("model load: ${if (load.alreadyLoaded) "already loaded" else "%.1f s".format(load.loadMs / 1000.0)}, threads=${load.threads}")
            val prep = QwenTtsRuntimeManager.prepareVoice(appContext, referenceAudio, referenceText)
            section("native: voice preparation", prep.log)
            record("voice preparation: ${if (prep.cached) "cached prompt reused" else "%.1f s".format(prep.prepMs / 1000.0)}; generation started")
            val generation = QwenTtsRuntimeManager.synthesize(text, languageId, output)
            section("native: generation", generation.log)
            val audioMs = WavFiles.durationMs(output)
            val result = if (audioMs == null || audioMs <= 0) {
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
            val summary = summary(text, load, prep, generation, audioMs)
            val details = summary + sections
            record(details)
            result.copy(details = details)
        } catch (e: CancellationException) {
            // Cancel must not leave the native model resident.
            output.delete()
            withContext(NonCancellable) { QwenTtsRuntimeManager.release() }
            throw e
        } catch (e: QwenTtsException) {
            output.delete()
            section("native (before the failure)", e.log)
            val details = "FAILED: ${e.message}\n$sections"
            record(details)
            VoiceBenchmarkResult.failed(id, VoiceBenchmarkStatus.ERROR, e.message).copy(details = details)
        }
    }

    // The three costs and the RTF, in one place. Generation time (and RTF)
    // cover neither model loading nor voice preparation.
    private fun summary(
        text: String,
        load: QwenTtsRuntimeManager.LoadInfo,
        prep: QwenTtsRuntimeManager.PrepInfo,
        generation: QwenTtsRuntimeManager.SynthInfo,
        audioMs: Long?,
    ): String {
        fun s(ms: Long) = "%.2f s".format(ms / 1000.0)
        val audio = (audioMs ?: 0L)
        val rtf = if (audio > 0) generation.generationMs.toDouble() / audio else 0.0
        return buildString {
            append("Qwen3-TTS ${size.label} Base · ${text.length} chars\n")
            append("Threads:            ${load.threads}\n")
            append("Reference:          ${"%.1f".format(prep.referenceSeconds)} s recorded, ${"%.1f".format(prep.usedSeconds)} s used\n")
            append("1 Model load:       ").append(if (load.alreadyLoaded) "0 (already loaded)" else s(load.loadMs)).append('\n')
            append("2 Voice prep:       ").append(if (prep.cached) "0 (cached prompt reused, reference NOT re-analysed)" else s(prep.prepMs)).append('\n')
            append("3-6 Generation:     ${s(generation.generationMs)}  (tokenize + talker + vocoder + WAV, see native report below)\n")
            generation.firstAudioMs?.let { append("    first audio chunk: ${s(it)}\n") }
            append("7 Total (all):      ${s((if (load.alreadyLoaded) 0 else load.loadMs) + prep.prepMs + generation.generationMs)}\n")
            append("8 Audio duration:   ${s(audio)}\n")
            append("9 RTF:              ${"%.2f".format(rtf)}  (generation / audio)\n")
        }
    }

    // The app log is where a phone-only investigation looks: without this the
    // native side's timings went to stderr, which Android discards.
    private fun record(details: String) {
        runCatching { AppContainer.get(appContext).appLog.record("QWEN_TTS", details) }
    }

    private companion object {
        // Codec language ids from qwen3-tts.cpp (qwen3_tts.h).
        val LANGUAGE_IDS = mapOf("en" to 2050, "ru" to 2069)
    }
}
