package ai.localstudio.app.benchmark

import ai.localstudio.app.log.AppLog
import ai.localstudio.app.whisper.WarmupSample
import ai.localstudio.core.benchmark.BenchmarkAudioFile
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkRunner
import ai.localstudio.core.benchmark.BenchmarkStatus
import ai.localstudio.core.benchmark.BenchmarkSummary
import ai.localstudio.core.benchmark.TranscriptionEngine
import ai.localstudio.core.util.describeForUser
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface BenchmarkUiState {
    data object Idle : BenchmarkUiState
    /**
     * [status] is the latest [BenchmarkRunner.run] `onStatus` line — see that
     * parameter's own doc comment for why this exists alongside
     * [completed]/[total]: a real run can sit on the same (completed, total)
     * pair for minutes while a big model loads or one file transcribes, with
     * nothing else to show for it otherwise. [mode] is the run's own
     * [BenchmarkPerformanceMode] — carried here (not just passed once to
     * [BenchmarkOrchestrator.start]) so [BenchmarkActivity] can read it from
     * `onResume`/`onPause` to decide whether `Window.setSustainedPerformanceMode`
     * should be on right now, without keeping its own separate copy that
     * could drift from the run actually in flight.
     */
    data class Running(val completed: Int, val total: Int, val status: String = "", val mode: BenchmarkPerformanceMode = BenchmarkPerformanceMode.MAXIMUM) : BenchmarkUiState
    data class Done(val report: BenchmarkReport, val savedAs: String) : BenchmarkUiState
    data class Failed(val message: String) : BenchmarkUiState
}

/**
 * Owns one benchmark run for the whole app, not for a screen — same
 * reasoning [ai.localstudio.app.whisper.FileTranscriptionRunner] already
 * follows (see its own doc comment): a benchmark over hundreds of files
 * across several engines can run far longer than a single file
 * transcription ever did, so it needs the same protection from Activity
 * recreation, not less.
 */
class BenchmarkOrchestrator(
    private val runner: BenchmarkRunner,
    private val reportStore: BenchmarkReportStore,
    private val engineProvider: () -> List<TranscriptionEngine>,
    private val scope: CoroutineScope,
    /**
     * Every load/warm-up/per-file status line drives the on-screen
     * [BenchmarkUiState.Running.status] directly — [appLog] only gets the
     * high-signal events (run start/finish/failure, each file's own
     * *error*, never a routine success). A real device report is why:
     * Журнал ошибок is a single, app-wide, ~1000-line capped log (see
     * [AppLog]'s own doc comment), and a run's own routine chatter — two
     * lines per file, times every file, times every engine — was rotating
     * away not just its own early history but every *other* feature's log
     * entries too, well before the run itself finished.
     */
    private val appLog: AppLog,
    /** For [BenchmarkWarmup.resolve] — see [warmupSample]'s own doc comment. */
    private val context: Context,
    /** Starts [ai.localstudio.app.benchmark.BenchmarkService] — a run over several GB-scale models can take many minutes, and without a foreground service the OS kills the whole process the moment the screen locks, same gap [ai.localstudio.app.whisper.FileTranscriptionRunner] already had fixed for it. A real device report: a run on the biggest installed model just vanished, mid-run, after a few minutes with the screen off. */
    private val onBenchmarkStarted: () -> Unit = {},
) {
    private val _state = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Idle)
    val state: StateFlow<BenchmarkUiState> = _state

    private var job: Job? = null

    private val thermalGuard = ThermalGuard(context)

    private companion object {
        /** Fixed pause between engines in [BenchmarkPerformanceMode.COOL_DOWN], on top of whatever [ThermalGuard.waitUntilSafe] itself waits for — a device can report a safe thermal status again well before it has actually recovered close to a resting state, so this mode's whole point (giving the device real recovery time) still applies a floor even when the guard returns immediately. */
        const val COOL_DOWN_MIN_DELAY_MS = 60_000L
    }

    /** A fixed short sample every engine warms up on instead of one of the user's own files — see [WarmupSample]'s own doc comment. Resolved once (the underlying asset never changes) rather than re-copied every run. */
    private val warmupSample: BenchmarkAudioFile by lazy {
        val file = WarmupSample.resolve(context)
        BenchmarkAudioFile(
            uri = file.toURI().toString(),
            fileName = file.name,
            fileSizeBytes = file.length(),
            durationMs = WarmupSample.DURATION_MS,
            sampleRateHz = WarmupSample.SAMPLE_RATE_HZ,
            channels = 1,
        )
    }

    /**
     * [mode] selects how the run is paced — see [BenchmarkPerformanceMode]'s
     * own doc comment. Everything else about the run (engines, files,
     * language, per-call timeouts) is identical across all three modes on
     * purpose: comparing MAXIMUM against SUSTAINED is only valid if nothing
     * else differs (requirement #5).
     */
    fun start(files: List<BenchmarkAudioFile>, mode: BenchmarkPerformanceMode = BenchmarkPerformanceMode.MAXIMUM) {
        if (job?.isActive == true || files.isEmpty()) return
        onBenchmarkStarted()
        val engines = engineProvider()
        val total = files.size * engines.size
        val sustainedSupported = SustainedPerformanceSupport.isSupported(context)
        val sustainedActive = mode == BenchmarkPerformanceMode.SUSTAINED && sustainedSupported
        _state.value = BenchmarkUiState.Running(completed = 0, total = total, status = "Starting…", mode = mode)
        appLog.record("BENCHMARK", "run starting: mode=$mode, ${engines.size} engine(s) x ${files.size} file(s)")
        if (mode == BenchmarkPerformanceMode.SUSTAINED && !sustainedSupported) {
            appLog.record("BENCHMARK", "sustained_mode=unsupported")
        }
        // Execution order, not grouped by file/engine like the report's own
        // structure — see BenchmarkPerformanceTrend's own doc comment for
        // why that distinction matters for a degradation measurement.
        val orderedRtfs = mutableListOf<Double>()
        var isFirstEngine = true
        job = scope.launch {
            try {
                val output = runner.run(
                    engines = engines,
                    files = files,
                    // "transcribe", not "translate": the only task this app's
                    // whisper.cpp path actually exercises today — recorded
                    // explicitly (see BenchmarkReport.sharedTask's own doc
                    // comment) rather than left implicit.
                    forcedLanguage = null,
                    memorySamplerMb = { Debug.getNativeHeapAllocatedSize() / (1024 * 1024) },
                    freeRamMbSampler = { freeRamMb() },
                    thermalStatusSampler = { thermalGuard.currentStatusLabel() },
                    thermalHeadroomSampler = { thermalGuard.currentHeadroom() },
                    onProgress = { completed, done ->
                        val current = _state.value as? BenchmarkUiState.Running
                        _state.value = BenchmarkUiState.Running(completed, done, current?.status.orEmpty(), mode)
                    },
                    onStatus = { status ->
                        val current = _state.value as? BenchmarkUiState.Running
                        _state.value = BenchmarkUiState.Running(current?.completed ?: 0, current?.total ?: total, status, mode)
                    },
                    warmupSample = warmupSample,
                    // Real device report: a benchmark that kept loading
                    // multi-GB models back-to-back while the device was
                    // already thermally throttled produced 13-minute
                    // warm-ups and then a 100% failure rate — see
                    // ThermalGuard's own doc comment. Only COOL_DOWN actually
                    // pauses for this: MAXIMUM and SUSTAINED run back to back
                    // on purpose, since throttling behavior under sustained
                    // load — not avoiding it — is exactly what those two
                    // modes are meant to be compared under (requirement #5).
                    beforeEngine = {
                        // Real device report: this fires before every
                        // engine, the first one included — without the
                        // isFirstEngine check, COOL_DOWN burned a full fixed
                        // delay before the run had even loaded its first
                        // model, with nothing yet to cool down from.
                        if (mode == BenchmarkPerformanceMode.COOL_DOWN && !isFirstEngine) {
                            thermalGuard.waitUntilSafe { message ->
                                appLog.record("BENCHMARK", message)
                                val current = _state.value as? BenchmarkUiState.Running
                                _state.value = BenchmarkUiState.Running(current?.completed ?: 0, current?.total ?: total, message, mode)
                            }
                            val current = _state.value as? BenchmarkUiState.Running
                            _state.value = BenchmarkUiState.Running(current?.completed ?: 0, current?.total ?: total, "Cooling down…", mode)
                            delay(COOL_DOWN_MIN_DELAY_MS)
                        }
                        isFirstEngine = false
                    },
                    onFileComplete = { _, file, metrics ->
                        // Persisted the moment each individual file finishes,
                        // not batched by engine or to the very end — see
                        // BenchmarkReportStore's own doc comment for the
                        // real device report this exists for.
                        reportStore.saveFileResult(file, metrics)
                        if (metrics.status == BenchmarkStatus.SUCCESS) {
                            metrics.rtf?.let { orderedRtfs += it }
                        } else if (metrics.status == BenchmarkStatus.ERROR) {
                            appLog.record("BENCHMARK", "${metrics.modelId}: ${file.fileName} — ERROR: ${metrics.errorMessage}")
                        }
                    },
                )
                val report = reportStore.buildReport(
                    output,
                    sharedTask = "transcribe",
                    sharedForcedLanguage = null,
                    performanceMode = mode.name,
                    sustainedModeSupported = sustainedSupported,
                    sustainedModeActive = sustainedActive,
                    performanceTrend = BenchmarkSummary.computeTrend(orderedRtfs),
                )
                val savedAs = reportStore.save(report)
                appLog.record("BENCHMARK", "run finished, saved as $savedAs")
                _state.value = BenchmarkUiState.Done(report, savedAs)
            } catch (e: CancellationException) {
                // cancel() already set _state to Idle synchronously — real
                // device report: this catch used to fall through to the
                // generic Exception branch below (CancellationException IS
                // an Exception), overwriting that Idle state with Failed
                // right after the user pressed Stop, which looked like the
                // run had crashed instead of just stopping.
                appLog.record("BENCHMARK", "run stopped")
            } catch (e: Exception) {
                appLog.record("BENCHMARK", "run failed: ${e.describeForUser()}")
                _state.value = BenchmarkUiState.Failed(e.describeForUser())
            }
        }
    }

    /** Device-wide free RAM in MB — see [ai.localstudio.core.benchmark.BenchmarkRunMetrics.freeRamMb]'s own doc comment for why this is sampled separately from the app's own native heap ([Debug.getNativeHeapAllocatedSize]). */
    private fun freeRamMb(): Long? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    /**
     * Stops scheduling further (file, engine) pairs — an in-flight native
     * transcribe call still runs to completion, the same cooperative-
     * cancellation limitation every whisper.cpp call in this app already
     * has (see [ai.localstudio.app.whisper.WhisperCppSpeechModel.requestCancel]'s
     * own doc comment): nothing here claims otherwise.
     */
    fun cancel() {
        job?.cancel()
        _state.value = BenchmarkUiState.Idle
    }
}
