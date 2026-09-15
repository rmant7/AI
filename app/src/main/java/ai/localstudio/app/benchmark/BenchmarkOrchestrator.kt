package ai.localstudio.app.benchmark

import ai.localstudio.core.benchmark.BenchmarkAudioFile
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkRunner
import ai.localstudio.core.benchmark.TranscriptionEngine
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface BenchmarkUiState {
    data object Idle : BenchmarkUiState
    data class Running(val completed: Int, val total: Int) : BenchmarkUiState
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
) {
    private val _state = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Idle)
    val state: StateFlow<BenchmarkUiState> = _state

    private var job: Job? = null

    fun start(files: List<BenchmarkAudioFile>) {
        if (job?.isActive == true || files.isEmpty()) return
        val engines = engineProvider()
        _state.value = BenchmarkUiState.Running(completed = 0, total = files.size * engines.size)
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
                    onProgress = { completed, total -> _state.value = BenchmarkUiState.Running(completed, total) },
                )
                val report = reportStore.buildReport(output, sharedTask = "transcribe", sharedForcedLanguage = null)
                val savedAs = reportStore.save(report)
                _state.value = BenchmarkUiState.Done(report, savedAs)
            } catch (e: Exception) {
                _state.value = BenchmarkUiState.Failed(e.message ?: e.toString())
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
