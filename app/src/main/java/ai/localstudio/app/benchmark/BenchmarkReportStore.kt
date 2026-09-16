package ai.localstudio.app.benchmark

import ai.localstudio.app.log.AppLog
import ai.localstudio.core.benchmark.BenchmarkAudioFile
import ai.localstudio.core.benchmark.BenchmarkDeviceInfo
import ai.localstudio.core.benchmark.BenchmarkPerformanceTrend
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkRunMetrics
import ai.localstudio.core.benchmark.BenchmarkRunOutput
import ai.localstudio.core.benchmark.BenchmarkStatus
import ai.localstudio.core.util.describeForUser
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a raw [BenchmarkRunOutput] (Android-agnostic, from `core`) into the
 * full, reproducible [BenchmarkReport] by attaching device/app metadata,
 * then saves it:
 *
 * 1. Internally (`filesDir/benchmarks/`), so [ai.localstudio.app.BenchmarkActivity]
 *    can re-read/share past runs through the app's own `FileProvider`, and
 *    mirrored as one JSON to `Download/Transcripts/benchmark/` via
 *    MediaStore — the single reproducible record (every file, every
 *    engine, every metric) a run weeks from now can be compared against.
 *    Both happen once, in [save], after the whole run finishes.
 * 2. One plain-text file per (engine, audio file), under
 *    `Download/Transcripts/benchmark/<modelId>/<audio file name>.txt` — the
 *    per-model layout this exists for: opening one model's folder shows
 *    every one of its transcripts side by side, so comparing what Tiny
 *    produced against what Large produced for the same recording doesn't
 *    require digging through the combined JSON at all. **Written
 *    incrementally, via [saveFileResult]**, the moment each individual
 *    file finishes — not batched by engine, and not batched into [save] —
 *    a real device report is why: with results only ever saved once, at
 *    the very end, a run comparing several GB-scale models produced *zero*
 *    inspectable output for over half an hour, all-or-nothing, and even
 *    "once per engine" still meant waiting many minutes on a device where
 *    a single *file* can itself take that long.
 */
class BenchmarkReportStore(private val context: Context, private val appLog: AppLog) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun buildReport(
        output: BenchmarkRunOutput,
        sharedTask: String,
        sharedForcedLanguage: String?,
        performanceMode: String = "MAXIMUM",
        sustainedModeSupported: Boolean = false,
        sustainedModeActive: Boolean = false,
        performanceTrend: BenchmarkPerformanceTrend? = null,
    ): BenchmarkReport = BenchmarkReport(
        appVersion = appVersion(),
        device = deviceInfo(),
        startedAtEpochMs = output.startedAtEpochMs,
        finishedAtEpochMs = output.finishedAtEpochMs,
        sharedTask = sharedTask,
        sharedForcedLanguage = sharedForcedLanguage,
        engines = output.engines,
        files = output.files,
        performanceMode = performanceMode,
        sustainedModeSupported = sustainedModeSupported,
        sustainedModeActive = sustainedModeActive,
        performanceTrend = performanceTrend,
    )

    /**
     * Writes one .txt for a single (file, engine) result — call the moment
     * each file finishes, not batched with anything else in the run. See
     * this class's own doc comment for why. Failure here (a name MediaStore
     * rejects, a full disk) is logged, not thrown — one file's write
     * failing must never lose or delay any other file's own result.
     */
    fun saveFileResult(file: BenchmarkAudioFile, metrics: BenchmarkRunMetrics) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val baseName = file.fileName.substringBeforeLast('.', file.fileName)
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Transcripts/benchmark/${metrics.modelId}/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("contentResolver.insert returned null")
            context.contentResolver.openOutputStream(uri)?.use { it.write(transcriptFileText(file.fileName, metrics).toByteArray()) }
                ?: error("openOutputStream returned null for $uri")
        }.onFailure { e ->
            appLog.record("BENCHMARK", "failed to write ${metrics.modelId}/$baseName.txt: ${e.describeForUser()}")
        }
    }

    /** Returns the internal file's name (for display) — the same "saved as X" convention TranscribeActivity already uses. */
    fun save(report: BenchmarkReport): String {
        val text = json.encodeToString(BenchmarkReport.serializer(), report)
        val dir = File(context.filesDir, "benchmarks").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.startedAtEpochMs))
        val name = "benchmark-$stamp.json"
        File(dir, name).writeText(text)
        appLog.record("BENCHMARK", "saved internally as $name")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            appLog.record("BENCHMARK", "Downloads copy skipped: API ${Build.VERSION.SDK_INT} < 29")
            return name
        }
        copyReportToDownloads(name, text)
        return name
    }

    private fun copyReportToDownloads(name: String, text: String) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Transcripts/benchmark/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("contentResolver.insert returned null")
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("openOutputStream returned null for $uri")
        }.onFailure { e ->
            appLog.record("BENCHMARK", "failed to copy $name to Download/Transcripts/benchmark/: ${e.describeForUser()}")
        }
    }

    private fun transcriptFileText(fileName: String, metrics: BenchmarkRunMetrics): String {
        val header = buildString {
            appendLine("file: $fileName")
            appendLine("backend: ${metrics.backendId} ${metrics.backendVersion}")
            appendLine("model: ${metrics.modelId} (${metrics.precision})")
            appendLine("status: ${metrics.status}")
            appendLine("processing: ${metrics.processingMs} ms" + (metrics.rtf?.let { " (RTF ${"%.3f".format(Locale.US, it)})" } ?: ""))
            metrics.threads?.let { appendLine("threads: $it") }
            metrics.memoryMb?.let { appendLine("native heap: $it MB") }
            metrics.freeRamMbBefore?.let { appendLine("free RAM before: $it MB") }
            metrics.freeRamMb?.let { appendLine("free RAM after: $it MB") }
            metrics.thermalStatus?.let { appendLine("thermal status: $it") }
            metrics.thermalHeadroom?.let { appendLine("thermal headroom: ${"%.3f".format(Locale.US, it)}") }
        }
        val body = when (metrics.status) {
            BenchmarkStatus.SUCCESS -> metrics.transcriptText
            else -> "[${metrics.status}] ${metrics.errorMessage ?: "no error message"}"
        }
        return "$header\n$body"
    }

    private fun appVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode})"
    }.getOrDefault("unknown")

    private fun deviceInfo(): BenchmarkDeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        return BenchmarkDeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            apiLevel = Build.VERSION.SDK_INT,
            ramMb = memInfo.totalMem / (1024 * 1024),
            cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
        )
    }
}
