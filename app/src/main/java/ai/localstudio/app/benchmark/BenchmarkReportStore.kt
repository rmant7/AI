package ai.localstudio.app.benchmark

import ai.localstudio.core.benchmark.BenchmarkDeviceInfo
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkRunMetrics
import ai.localstudio.core.benchmark.BenchmarkRunOutput
import ai.localstudio.core.benchmark.BenchmarkStatus
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
 * then saves it three ways:
 *
 * 1. Internally (`filesDir/benchmarks/`), so [ai.localstudio.app.BenchmarkActivity]
 *    can re-read/share past runs through the app's own `FileProvider`.
 * 2. The full JSON, mirrored to `Download/Transcripts/benchmark/` via
 *    MediaStore — the single reproducible record (every file, every
 *    engine, every metric) a run weeks from now can be compared against.
 * 3. One plain-text file per (engine, audio file) under
 *    `Download/Transcripts/benchmark/<modelId>/<audio file name>.txt` — the
 *    per-model layout this exists for: opening one model's folder shows
 *    every one of its transcripts side by side, so comparing what Tiny
 *    produced against what Large produced for the same recording doesn't
 *    require digging through the combined JSON at all.
 */
class BenchmarkReportStore(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun buildReport(
        output: BenchmarkRunOutput,
        sharedTask: String,
        sharedForcedLanguage: String?,
    ): BenchmarkReport = BenchmarkReport(
        appVersion = appVersion(),
        device = deviceInfo(),
        startedAtEpochMs = output.startedAtEpochMs,
        finishedAtEpochMs = output.finishedAtEpochMs,
        sharedTask = sharedTask,
        sharedForcedLanguage = sharedForcedLanguage,
        engines = output.engines,
        files = output.files,
    )

    /** Returns the internal file's name (for display) — the same "saved as X" convention TranscribeActivity already uses. */
    fun save(report: BenchmarkReport): String {
        val text = json.encodeToString(BenchmarkReport.serializer(), report)
        val dir = File(context.filesDir, "benchmarks").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(report.startedAtEpochMs))
        val name = "benchmark-$stamp.json"
        File(dir, name).writeText(text)
        copyReportToDownloads(name, text)
        copyPerModelTranscriptsToDownloads(report)
        return name
    }

    private fun copyReportToDownloads(name: String, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Transcripts/benchmark/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }

    /**
     * One .txt per (engine, file), under `Download/Transcripts/benchmark/<modelId>/`
     * — deliberately per-model directories, not per-file: comparing Tiny vs.
     * Large for the *same* recording is the actual use case, and that only
     * reads naturally when each model's whole batch of output sits in its
     * own folder. Best-effort per file: one write failing (a name MediaStore
     * rejects, a full disk) must not lose every other file's already-written
     * transcript, so each one is its own `runCatching`.
     */
    private fun copyPerModelTranscriptsToDownloads(report: BenchmarkReport) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        for (fileResult in report.files) {
            val baseName = fileResult.file.fileName.substringBeforeLast('.', fileResult.file.fileName)
            for (metrics in fileResult.results) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$baseName.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Transcripts/benchmark/${metrics.modelId}/")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
                    context.contentResolver.openOutputStream(uri)?.use { it.write(transcriptFileText(fileResult.file.fileName, metrics).toByteArray()) }
                }
            }
        }
    }

    private fun transcriptFileText(fileName: String, metrics: BenchmarkRunMetrics): String {
        val header = buildString {
            appendLine("file: $fileName")
            appendLine("backend: ${metrics.backendId} ${metrics.backendVersion}")
            appendLine("model: ${metrics.modelId} (${metrics.precision})")
            appendLine("status: ${metrics.status}")
            appendLine("processing: ${metrics.processingMs} ms" + (metrics.rtf?.let { " (RTF ${"%.3f".format(Locale.US, it)})" } ?: ""))
            metrics.memoryMb?.let { appendLine("native heap: $it MB") }
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
