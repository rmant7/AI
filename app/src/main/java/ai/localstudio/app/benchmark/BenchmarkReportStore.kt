package ai.localstudio.app.benchmark

import ai.localstudio.core.benchmark.BenchmarkDeviceInfo
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkRunOutput
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
 * then saves it — internally (so [ai.localstudio.app.BenchmarkActivity] can
 * re-read past runs) and, same as [ai.localstudio.app.whisper.FileTranscriptionRunner]'s
 * own Downloads copy, into `Download/Benchmarks/` via MediaStore so it is
 * reachable without digging into the app's private storage. Each backend's
 * actual transcript text already travels inside the JSON itself
 * ([ai.localstudio.core.benchmark.BenchmarkRunMetrics.transcriptText]) —
 * deliberately not also written out as a pile of separate per-file,
 * per-backend .txt files: everything needed to compare quality later is
 * already in the one JSON, and multiplying file count by backend count by
 * two (result + duplicate .txt) for a hundreds-of-files run is exactly the
 * kind of clutter this benchmark's own output should not add to.
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
        copyToDownloads(name, text)
        return name
    }

    private fun copyToDownloads(name: String, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Benchmarks/")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
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
