package ai.localstudio.app.voicebenchmark

import ai.localstudio.app.AppContainer
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Everything the Voice Benchmark screen must not lose: the running benchmark
 * (and the ~1 GB model behind it), the results, an ongoing recording and the
 * inputs. A screen is destroyed and rebuilt by things like changing the app's
 * language or rotating the phone; this object lives through that, so the work
 * carries on and the new screen just draws the same state again. Results also
 * go to disk ([VoiceBenchmarkStore]), so they outlive the process too.
 */
class VoiceBenchmarkViewModel private constructor(application: Application) : AndroidViewModel(application) {

    /** What the status line says — formatted (and translated) by the screen, so a language change re-translates it. */
    sealed interface Status {
        data object Idle : Status
        data class Running(val engineName: String) : Status
        data object Done : Status
        data object Cancelled : Status
        data class Live(val audioMs: Long, val elapsedMs: Long) : Status
        data class Native(val line: String) : Status
    }

    data class Recording(
        val recording: Boolean = false,
        val level: Float = 0f,
        val elapsedMs: Long = 0,
        val hasRecording: Boolean = false,
        val durationMs: Long = 0,
    )

    data class Ui(
        val results: Map<String, VoiceBenchmarkResult> = emptyMap(),
        val runningIds: List<String> = emptyList(),
        val running: Boolean = false,
        val status: Status = Status.Idle,
        val profileReport: String = "",
        val recording: Recording = Recording(),
    )

    // Inputs the screen restores after being recreated.
    var inputsInitialized = false
    var languageIndex = 0
    var presetIndex = 0
    var text = ""
    val engineChecked = HashMap<String, Boolean>()
    var trimReference = true
    var threadsIndex = 0
    var streamingIndex = 1
    var vocoderThreadsIndex = 0

    private val app = application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    val resultsDir = File(app.filesDir, "voice_benchmark/results").also { it.mkdirs() }
    val recorder = ReferenceVoiceRecorder(app)
    val runner = VoiceBenchmarkRunner(
        listOf(
            AndroidTtsBenchmarkEngine(app, resultsDir),
            // The 1.7B and Chatterbox engines are still placeholders: not offered until they exist.
            Qwen3TtsBenchmarkEngine(app, Qwen3TtsBenchmarkEngine.Size.SMALL, resultsDir),
        ),
    )
    private val store = VoiceBenchmarkStore(File(app.filesDir, "voice_benchmark/results.json"))

    private val _state = MutableStateFlow(
        Ui(
            results = LinkedHashMap<String, VoiceBenchmarkResult>().also { m -> store.load().forEach { m[it.engineId] = it } },
            recording = Recording(hasRecording = recorder.hasRecording, durationMs = recorder.durationMs()),
        ),
    )
    val state: StateFlow<Ui> = _state

    fun engineName(id: String): String = runner.engines.firstOrNull { it.id == id }?.displayName ?: id

    // ── reference recording ───────────────────────────────────────────────

    fun startRecording(): Boolean {
        val started = recorder.start(
            onLevel = { level -> _state.update { it.copy(recording = it.recording.copy(level = level)) } },
            onElapsed = { ms -> _state.update { it.copy(recording = it.recording.copy(elapsedMs = ms)) } },
            onFinished = {
                _state.update {
                    it.copy(recording = Recording(hasRecording = recorder.hasRecording, durationMs = recorder.durationMs()))
                }
            },
        )
        if (started) _state.update { it.copy(recording = it.recording.copy(recording = true, elapsedMs = 0)) }
        return started
    }

    fun stopRecording() = recorder.stop()

    // ── running ───────────────────────────────────────────────────────────

    fun runSelected(ids: List<String>, request: VoiceBenchmarkRunner.Request) = launchRun {
        if (ids.any { it.startsWith("qwen3_tts") }) AppContainer.get(app).releaseLocalModels()
        for (id in ids) runEngine(id, request)
    }

    fun repeat(id: String, request: VoiceBenchmarkRunner.Request) = launchRun {
        if (id.startsWith("qwen3_tts")) AppContainer.get(app).releaseLocalModels()
        runEngine(id, request)
    }

    /** Two runs of the Qwen 0.6B on [texts]: the first from a cold start, then the same voice with new text. */
    fun runQwenProfile(
        request: (text: String) -> VoiceBenchmarkRunner.Request,
        texts: List<String>,
        labels: List<String>,
    ) = launchRun {
        AppContainer.get(app).releaseLocalModels()
        QwenTtsRuntimeManager.release()
        QwenTtsRuntimeManager.clearVoiceCache(app)
        val report = StringBuilder()
        val warm = mutableListOf<VoiceBenchmarkResult>()
        texts.forEachIndexed { index, text ->
            _state.update { it.copy(profileReport = summary(warm) + report.toString() + labels[index] + " …") }
            val result = runEngine(QWEN_06B_ID, request(text))
            if (index > 0 && result.success) warm += result
            report.append("===== ").append(labels[index]).append(" =====\n\"").append(text).append("\"\n")
            report.append(result.details ?: result.error ?: result.status.name).append("\n\n")
            _state.update { it.copy(profileReport = summary(warm) + report.toString()) }
        }
    }

    // Repeated runs of the same setup differ by tens of percent, so the warm runs are summarised:
    // the code-generation cost per frame (total minus vocoder decode, over frames) and the RTF of each.
    private fun summary(warm: List<VoiceBenchmarkResult>): String {
        if (warm.isEmpty()) return ""
        fun num(re: String, text: String) = Regex(re).find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val perFrame = warm.mapNotNull { r ->
            val d = r.details ?: return@mapNotNull null
            val code = num("Code\\+streaming:\\s+(\\d+) ms", d) ?: return@mapNotNull null
            val decode = num("Streaming decode:\\s*(\\d+) ms", d) ?: 0.0
            val frames = num("emitted=(\\d+)", d)?.takeIf { it > 0 } ?: return@mapNotNull null
            (code - decode) / 1000.0 / frames
        }
        val rtfs = warm.mapNotNull { it.rtf }
        fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        fun list(v: List<Double>) = v.joinToString(", ") { "%.2f".format(it) }
        return buildString {
            append("##### WARM RUNS (${warm.size}) #####\n")
            append("code s/frame: ${list(perFrame)}  -> median ${"%.3f".format(median(perFrame))}\n")
            append("RTF: ${list(rtfs)}  -> median ${"%.2f".format(median(rtfs))}\n\n")
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clearResults() {
        _state.value.results.values.forEach { it.audioFile?.delete() }
        _state.update { it.copy(results = emptyMap(), runningIds = emptyList(), status = Status.Idle, profileReport = "") }
        store.save(emptyList())
    }

    private suspend fun runEngine(id: String, request: VoiceBenchmarkRunner.Request): VoiceBenchmarkResult {
        _state.update {
            it.copy(
                results = it.results.filterKeys { key -> key != id }.also { _ -> it.results[id]?.audioFile?.delete() },
                runningIds = it.runningIds + id,
                status = Status.Running(engineName(id)),
            )
        }
        val result = runner.runOne(id, request).copy(language = request.language)
        _state.update {
            // A finished engine keeps its place in the list of results.
            val order = (it.results.keys + id).toList()
            val merged = LinkedHashMap<String, VoiceBenchmarkResult>()
            order.forEach { key -> (if (key == id) result else it.results[key])?.let { r -> merged[key] = r } }
            it.copy(results = merged, runningIds = it.runningIds - id)
        }
        store.save(_state.value.results.values)
        return result
    }

    private fun launchRun(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        _state.update { it.copy(running = true) }
        job = scope.launch {
            // While a Qwen generation runs, show how fast it is going and what the
            // native runtime is printing: waiting for the final report is not an option.
            val ticker = launch {
                var liveOffset = 0
                while (isActive) {
                    delay(1000)
                    QwenTtsRuntimeManager.liveLog()?.let { live ->
                        if (live.length < liveOffset) liveOffset = 0
                        val end = live.lastIndexOf('\n') + 1
                        if (end > liveOffset) {
                            val fresh = live.substring(liveOffset, end).lines().filter { it.isNotBlank() }
                            liveOffset = end
                            fresh.lastOrNull()?.let { line -> _state.update { it.copy(status = Status.Native(line.trim())) } }
                        }
                    }
                    QwenTtsRuntimeManager.generationProgress()?.let { (audioMs, elapsedMs) ->
                        if (audioMs > 0) _state.update { it.copy(status = Status.Live(audioMs, elapsedMs)) }
                    }
                }
            }
            try {
                block()
                _state.update { it.copy(status = Status.Done) }
            } catch (e: CancellationException) {
                _state.update { it.copy(status = Status.Cancelled, runningIds = emptyList()) }
                throw e
            } finally {
                ticker.cancel()
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** True while a benchmark run is in progress. */
    val isRunning: Boolean get() = _state.value.running

    /** The screen was left for good: stop recording, and free the ~1 GB model unless a run still needs it. */
    fun onScreenClosed() {
        if (recorder.isRecording) recorder.stop()
        if (!isRunning) QwenTtsRuntimeManager.releaseAsync()
    }

    companion object {
        @Volatile
        private var instance: VoiceBenchmarkViewModel? = null

        /**
         * One per process, not per screen: leaving the screen (or Android recreating it) must not
         * cancel a run that takes minutes, and a screen opened again must show it still going.
         */
        fun get(app: Application): VoiceBenchmarkViewModel =
            instance ?: synchronized(this) { instance ?: VoiceBenchmarkViewModel(app).also { instance = it } }

        const val QWEN_06B_ID = "qwen3_tts_0.6b"
    }
}
