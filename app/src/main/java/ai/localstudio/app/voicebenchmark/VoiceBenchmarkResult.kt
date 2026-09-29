package ai.localstudio.app.voicebenchmark

import java.io.File

/** Why a benchmark result has no audio, in terms the screen can turn into a localized message. */
enum class VoiceBenchmarkStatus { OK, NOT_INSTALLED, MODEL_NOT_DOWNLOADED, UNSUPPORTED_DEVICE, UNSUPPORTED_LANGUAGE, ERROR }

/**
 * One engine's answer to one [VoiceBenchmarkRunner.Request]. Only objective
 * timing lives here — there is deliberately no quality or similarity score:
 * how a cloned voice sounds is judged by listening to [audioFile].
 *
 * [rtf] is real-time factor, generation time over audio length: below 1 the
 * engine is faster than the speech it produces.
 */
data class VoiceBenchmarkResult(
    val engineId: String,
    val success: Boolean,
    val audioFile: File?,
    val generationMs: Long?,
    val audioDurationMs: Long?,
    val rtf: Double?,
    val error: String?,
    val status: VoiceBenchmarkStatus = if (success) VoiceBenchmarkStatus.OK else VoiceBenchmarkStatus.ERROR,
    /** Model/engine start-up before generation began, when the engine can tell. */
    val loadMs: Long? = null,
    /** Building the reusable voice from the reference recording; 0 when an earlier run's was reused. */
    val voicePrepMs: Long? = null,
    /** Time from the start of generation to the first audio chunk. */
    val firstAudioMs: Long? = null,
    /** App PSS in MB before and after loading the model, and free device RAM before it. */
    val memoryBeforeMb: Long? = null,
    val memoryAfterMb: Long? = null,
    val availRamMb: Long? = null,
    /** A full diagnostic report (timings, native log) for engines that produce one. */
    val details: String? = null,
    /** The language this was generated in ("ru", "en", "he"), set by whoever ran it. */
    val language: String? = null,
) {
    companion object {
        fun ok(engineId: String, file: File, generationMs: Long, audioDurationMs: Long, loadMs: Long? = null) =
            VoiceBenchmarkResult(
                engineId = engineId,
                success = true,
                audioFile = file,
                generationMs = generationMs,
                audioDurationMs = audioDurationMs,
                rtf = if (audioDurationMs > 0) generationMs.toDouble() / audioDurationMs else null,
                error = null,
                loadMs = loadMs,
            )

        fun failed(engineId: String, status: VoiceBenchmarkStatus, error: String? = null) =
            VoiceBenchmarkResult(engineId, false, null, null, null, null, error, status)
    }
}
