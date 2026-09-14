package ai.localstudio.app.whisper

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-level ASR benchmark: RTF, first-result latency and total time for
 * whichever whisper.cpp model is currently selected, over whatever audio
 * files are placed on the device — the measurable subset of
 * docs/13-asr-pipeline-migration.md's "Benchmark" section (RTF, first-
 * result latency, average segment latency, total transcription time).
 * **Not** covered here, and needs to be gathered separately on the same
 * run: RAM/CPU (Android Studio Profiler, or `adb shell dumpsys meminfo` /
 * `top` while this runs), battery/thermal (`adb shell dumpsys
 * batterystats` / `adb shell cat /sys/class/thermal/thermal_zone*/temp`
 * before and after), and accuracy (WER against a reference transcript —
 * this harness has no ground truth to score against; compare
 * `asr-benchmark-results.csv`'s transcript output to one by hand, or wire
 * in a WER scorer separately if that becomes worth automating).
 *
 * Manual, on-device only — same convention as the sibling
 * [ai.localstudio.app.llama.MemoryPressureLifecycleTest]: every
 * [assumeTrue] below skips this (never fails it) when its precondition
 * isn't met, which is the case for every CI run.
 *
 * To actually run this on a Pixel 10 Pro (or any device):
 * 1. Install at least one whisper model through the app itself (Models >
 *    Voice tab) — whichever one is selected there
 *    ([WhisperStore.installedSeed]) is what gets benchmarked.
 * 2. Build a benchmark set matching docs/13-asr-pipeline-migration.md's
 *    "Benchmark" section: 10s / 1min / 10min / 60min clips, clean and
 *    noisy speech, at least one AMR file. Name them however's useful for
 *    reading the results (e.g. `10s-clean.wav`, `1min-noisy.amr`) —
 *    nothing here parses the filename, it's only echoed into the CSV.
 * 3. `adb push <your files> /sdcard/Android/data/<applicationId>/files/asr-benchmark/`
 *    (create the `asr-benchmark` directory first if needed).
 * 4. `./gradlew connectedDebugAndroidTest --tests "*WhisperBenchmarkTest*"`
 * 5. `adb pull /sdcard/Android/data/<applicationId>/files/asr-benchmark-results.csv`
 *    — also printed to logcat under the `WhisperBenchmark` tag as each
 *    file finishes, in case the run is killed partway through a very long
 *    clip.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBenchmarkTest {

    @Test
    fun benchmark() = runBlocking {
        assumeTrue("whisper_jni did not load for this ABI", WhisperBridge.isAvailable)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val whisperStore = WhisperStore(context)
        val benchmarkDir = File(context.getExternalFilesDir(null), "asr-benchmark")
        val files = benchmarkDir.listFiles { f -> f.isFile && MediaFileUtils.isMediaFile(f.name) }
            ?.sortedBy { it.name }
            .orEmpty()
        assumeTrue(
            "no benchmark audio files at ${benchmarkDir.absolutePath} - see WhisperBenchmarkTest's own doc comment",
            files.isNotEmpty(),
        )

        val seed = whisperStore.installedSeed()
        assumeTrue("no whisper model installed - see WhisperBenchmarkTest's own doc comment", seed != null)
        requireNotNull(seed)

        val modelFile = whisperStore.modelFile(seed)
        val descriptor = ModelDescriptor(
            id = seed.id,
            family = "whisper",
            version = "1",
            parameterCount = 1,
            capabilities = setOf(Capability.SPEECH_TO_TEXT),
            bindings = listOf(
                RuntimeBinding(
                    runtime = RuntimeKind.WHISPER_CPP,
                    artifact = modelFile.absolutePath,
                    fileSizeBytes = modelFile.length().coerceAtLeast(1),
                ),
            ),
        )

        val runtime = WhisperCppRuntime(context = context)
        val loadStartNs = System.nanoTime()
        val loaded = runtime.load(descriptor, descriptor.bindings.first()) as WhisperCppSpeechModel
        val loadMs = (System.nanoTime() - loadStartNs) / 1_000_000

        val rows = mutableListOf("model,file,audio_ms,load_ms,first_result_ms,total_ms,rtf,segments,chars")
        try {
            for (file in files) {
                val audioMs = durationMs(file)
                var firstResultMs: Long? = null
                var segmentCount = 0
                val startNs = System.nanoTime()

                val transcript = loaded.transcribeStreaming(
                    audio = AudioRef(uri = file.toURI().toString(), durationMs = audioMs),
                    language = null,
                ) {
                    if (firstResultMs == null) firstResultMs = (System.nanoTime() - startNs) / 1_000_000
                    segmentCount++
                }

                val totalMs = (System.nanoTime() - startNs) / 1_000_000
                val rtf = if (audioMs > 0) totalMs.toDouble() / audioMs else Double.NaN
                val row = "${seed.id},${file.name},$audioMs,$loadMs,${firstResultMs ?: -1},$totalMs,$rtf,$segmentCount,${transcript.text.length}"
                rows += row
                Log.i("WhisperBenchmark", row)
            }
        } finally {
            loaded.close()
        }

        File(context.getExternalFilesDir(null), "asr-benchmark-results.csv").writeText(rows.joinToString("\n"))
        Log.i("WhisperBenchmark", "done: ${rows.size - 1} file(s), results in asr-benchmark-results.csv")
    }

    private fun durationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }
}
