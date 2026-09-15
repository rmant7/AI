package ai.localstudio.core.benchmark

import kotlinx.serialization.Serializable

/** One file selected for the benchmark run — metadata gathered once before any engine sees it, so every engine is measured against the exact same input. */
@Serializable
data class BenchmarkAudioFile(
    val uri: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val durationMs: Long?,
    val sampleRateHz: Int?,
    val channels: Int?,
)

@Serializable
enum class BenchmarkStatus { SUCCESS, ERROR, TIMEOUT }

/** Everything measured for one (file, engine) pair. */
@Serializable
data class BenchmarkRunMetrics(
    val backendId: String,
    val backendVersion: String,
    val modelId: String,
    val precision: String,
    val threads: Int?,
    val forcedLanguage: String?,
    val detectedLanguage: String?,
    val processingMs: Long,
    /** processingMs / durationMs — null when the file's own duration couldn't be determined, never a divide-by-zero placeholder. */
    val rtf: Double?,
    /** Sampled once right after the call returns (Android's native heap allocation) — an approximation of memory *after* this run, not a true continuously-sampled peak during inference; see docs/16-stt-benchmark.md for why. Null on platforms/paths where even that isn't available. */
    val memoryMb: Long?,
    val status: BenchmarkStatus,
    val errorMessage: String? = null,
    val transcriptText: String = "",
    /** Free system RAM right after the call returns, in MB — unlike [memoryMb] (this app's own native heap), this is device-wide headroom, sampled to correlate a slow/failed run against memory pressure from other processes. Null where the sampler wasn't wired up. */
    val freeRamMb: Long? = null,
    /** [android.os.PowerManager.currentThermalStatus]'s name (e.g. "NONE", "MODERATE", "SEVERE") sampled right after the call returns — API 29+ only; null below that or where unavailable. See [ai.localstudio.app.benchmark.ThermalGuard]. */
    val thermalStatus: String? = null,
    /** [android.os.PowerManager.getThermalHeadroom] sampled right after the call returns — a normalized 0..1+ forecast, not a raw Celsius reading (Android exposes no public raw-temperature API to apps). API 30+ only; null below that or where the OS itself couldn't produce a value. */
    val thermalHeadroom: Float? = null,
)

@Serializable
data class BenchmarkFileResult(
    val file: BenchmarkAudioFile,
    val results: List<BenchmarkRunMetrics>,
)

/**
 * One engine's own setup cost, measured once per run before any file is
 * processed — kept separate from [BenchmarkRunMetrics.processingMs] so a
 * slow first load never pollutes the per-file steady-state numbers. See
 * [TranscriptionEngine.load]/[TranscriptionEngineSession.warmUp]'s own doc
 * comments for exactly what each duration covers.
 */
@Serializable
data class BenchmarkEngineSummary(
    val backendId: String,
    val displayName: String,
    val backendVersion: String,
    val modelId: String,
    val precision: String,
    val modelLoadMs: Long,
    /** Null when warm-up itself failed (see [warmUpFailed]) or there were no files to warm up with. */
    val warmInferenceMs: Long?,
    val warmUpFailed: Boolean,
    val loadFailed: Boolean,
    val loadErrorMessage: String? = null,
)

/**
 * First/average/last RTF across a run's successful files, in execution
 * order — not grouped by file like [BenchmarkReport.files] is, since the
 * whole point is to see whether the *last* file was slower than the
 * *first*. [degradationPercent] is `((last - first) / first) * 100`; null
 * whenever [firstRtf] is null or non-positive (nothing sane to divide by).
 */
@Serializable
data class BenchmarkPerformanceTrend(
    val firstRtf: Double?,
    val averageRtf: Double?,
    val lastRtf: Double?,
    val degradationPercent: Double?,
)

@Serializable
data class BenchmarkDeviceInfo(
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val ramMb: Long,
    val cpuAbi: String,
    val cpuCoreCount: Int,
)

/**
 * The full, structured, reproducible record of one benchmark run — saved as
 * JSON (see docs/16-stt-benchmark.md), not just a text log, specifically so
 * a run from several weeks ago (a different model build, a different
 * backend version) can be compared against a fresh one rather than trusted
 * from memory.
 */
@Serializable
data class BenchmarkReport(
    val benchmarkVersion: String = "1.0",
    val appVersion: String,
    val device: BenchmarkDeviceInfo,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    /**
     * Parameters this run tried to hold identical across every engine —
     * language/task are actually enforced the same for all; sampleRate is
     * whatever the source file already was (no engine in this app resamples
     * independently, so it is inherently shared, not forced) — see
     * docs/16-stt-benchmark.md's own note on what could and couldn't be
     * unified, per the explicit requirement to record that honestly rather
     * than silently.
     */
    val sharedTask: String,
    val sharedForcedLanguage: String?,
    val engines: List<BenchmarkEngineSummary>,
    val files: List<BenchmarkFileResult>,
    /** [ai.localstudio.app.benchmark.BenchmarkPerformanceMode]'s name — "MAXIMUM", "SUSTAINED", or "COOL_DOWN". A plain String, not the app-layer enum itself: this module has no Android dependency, and the mode is an Android OS concept (battery saver, Window.setSustainedPerformanceMode), not a core benchmarking one. Defaults to "MAXIMUM" for reports produced before this field existed. */
    val performanceMode: String = "MAXIMUM",
    /** [android.os.PowerManager.isSustainedPerformanceModeSupported] on this device — independent of [performanceMode], so a SUSTAINED run on an unsupported device is visible in the report rather than silently indistinguishable from one where it worked. */
    val sustainedModeSupported: Boolean = false,
    /** Whether `Window.setSustainedPerformanceMode(true)` was actually set during this run — false whenever [performanceMode] wasn't SUSTAINED, or it was but [sustainedModeSupported] was false (see requirement: log `sustained_mode=unsupported` and continue rather than fail). */
    val sustainedModeActive: Boolean = false,
    /** Null only for a report produced before this field existed, or a run with fewer than one successful file. */
    val performanceTrend: BenchmarkPerformanceTrend? = null,
)
