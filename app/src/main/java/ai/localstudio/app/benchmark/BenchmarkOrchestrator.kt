package ai.localstudio.app.benchmark

import ai.localstudio.app.log.AppLog
import ai.localstudio.app.whisper.WarmupSample
import ai.localstudio.core.benchmark.BenchmarkAudioFile
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkRunner
import ai.localstudio.core.benchmark.TranscriptionEngine
import ai.localstudio.core.util.describeForUser
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface BenchmarkUiState {
    data object Idle : BenchmarkUiState
    /** [status] is the latest [BenchmarkRunner.run] `onStatus` line — see that parameter's own doc comment for why this exists alongside [completed]/[total]: a real run can sit on the same (completed, total) pair for minutes while a big model loads or one file transcribes, with nothing else to show for it otherwise. */
    data class Running(val completed: Int, val total: Int, val status: String = "") : BenchmarkUiState
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
    /** Real device report: a run stuck on "0 / 35" for minutes with nothing in the app's own log either — every status line also lands here under the "BENCHMARK" tag, so a run stuck on a slow load/file is diagnosable from Журнал ошибок without needing the screen open. */
    private val appLog: AppLog,
    /** For [BenchmarkWarmup.resolve] — see [warmupSample]'s own doc comment. */
    private val context: Context,
    /** Starts [ai.localstudio.app.benchmark.BenchmarkService] — a run over several GB-scale models can take many minutes, and without a foreground service the OS kills the whole process the moment the screen locks, same gap [ai.localstudio.app.whisper.FileTranscriptionRunner] already had fixed for it. A real device report: a run on the biggest installed model just vanished, mid-run, after a few minutes with the screen off. */
    private val onBenchmarkStarted: () -> Unit = {},
) {
    private val _state = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Idle)
    val state: StateFlow<BenchmarkUiState> = _state

    private var job: Job? = null

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

    fun start(files: List<BenchmarkAudioFile>) {
        if (job?.isActive == true || files.isEmpty()) return
        onBenchmarkStarted()
        val engines = engineProvider()
        val total = files.size * engines.size
        _state.value = BenchmarkUiState.Running(completed = 0, total = total, status = "Starting…")
        appLog.record("BENCHMARK", "run starting: ${engines.size} engine(s) x ${files.size} file(s)")
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
                    onProgress = { completed, done ->
                        val current = _state.value as? BenchmarkUiState.Running
                        _state.value = BenchmarkUiState.Running(completed, done, current?.status.orEmpty())
                    },
                    onStatus = { status ->
                        appLog.record("BENCHMARK", status)
                        val current = _state.value as? BenchmarkUiState.Running
                        _state.value = BenchmarkUiState.Running(current?.completed ?: 0, current?.total ?: total, status)
                    },
                    warmupSample = warmupSample,
                )
                val report = reportStore.buildReport(output, sharedTask = "transcribe", sharedForcedLanguage = null)
                val savedAs = reportStore.save(report)
                appLog.record("BENCHMARK", "run finished, saved as $savedAs")
                _state.value = BenchmarkUiState.Done(report, savedAs)
            } catch (e: Exception) {
                appLog.record("BENCHMARK", "run failed: ${e.describeForUser()}")
                _state.value = BenchmarkUiState.Failed(e.describeForUser())
            }
        }
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
