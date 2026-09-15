package ai.localstudio.app.whisper

import android.content.Context
import java.io.File

/**
 * A fixed, bundled ~1.5s synthetic tone (`assets/warmup_sample.wav`, 16kHz
 * mono) used to warm up a whisper.cpp model right after loading it —
 * priming native buffers/thread pools and paging in the model's own
 * weights (mmap page faults, CPU governor ramp-up) — before it does real
 * work. Content is irrelevant for this purpose, only running the same
 * inference code path is, which is what makes a fixed sample strictly
 * better than warming up on whatever the caller happens to be about to
 * transcribe: bounded, predictable cost, and (for [ai.localstudio.app.benchmark.BenchmarkRunner])
 * comparable across separate runs.
 *
 * Two callers, same asset: [ai.localstudio.app.TranscribeActivity] warms
 * the selected model as soon as its screen opens, so the cold-start cost
 * is paid silently in the background instead of visibly inflating the
 * first real file's own transcription time; the STT benchmark warms every
 * engine on it before measuring anything (see docs/16-stt-benchmark.md).
 */
object WarmupSample {
    private const val ASSET_NAME = "warmup_sample.wav"
    const val DURATION_MS = 1_500L
    const val SAMPLE_RATE_HZ = 16_000

    /** Copies the asset into internal storage once — idempotent, a file already there is reused as-is. */
    fun resolve(context: Context): File {
        val file = File(context.filesDir, ASSET_NAME)
        if (!file.isFile || file.length() == 0L) {
            context.assets.open(ASSET_NAME).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file
    }
}
