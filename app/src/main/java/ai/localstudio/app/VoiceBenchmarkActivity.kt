package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityVoiceBenchmarkBinding
import ai.localstudio.app.databinding.ItemVoiceBenchmarkResultBinding
import ai.localstudio.app.voicebenchmark.AndroidTtsBenchmarkEngine
import ai.localstudio.app.voicebenchmark.ChatterboxBenchmarkEngine
import ai.localstudio.app.voicebenchmark.Qwen3TtsBenchmarkEngine
import ai.localstudio.app.voicebenchmark.ReferenceVoiceRecorder
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkEngine
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkResult
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkRunner
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkStatus
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Voice benchmark": record a reference voice, pick a language and a phrase,
 * and run every selected [VoiceBenchmarkEngine] on exactly the same input,
 * keeping each engine's WAV next to its timing so the voices can be
 * compared by ear. Knows nothing about how any engine makes its audio — that
 * is [VoiceBenchmarkRunner] and the engines' job.
 */
class VoiceBenchmarkActivity : AppCompatActivity() {

    private class Preset(val labelRes: Int, val textRes: Int?)

    private lateinit var binding: ActivityVoiceBenchmarkBinding
    private lateinit var recorder: ReferenceVoiceRecorder
    private lateinit var runner: VoiceBenchmarkRunner
    private lateinit var resultsDir: File

    private val engineChecks = LinkedHashMap<String, CheckBox>()
    private val resultViews = LinkedHashMap<String, ItemVoiceBenchmarkResultBinding>()
    private val results = LinkedHashMap<String, VoiceBenchmarkResult>()

    private var runJob: Job? = null
    private var player: MediaPlayer? = null
    private var playingButton: com.google.android.material.button.MaterialButton? = null

    private val languages = listOf(
        "ru" to R.string.voice_bench_lang_ru,
        "en" to R.string.voice_bench_lang_en,
        "he" to R.string.voice_bench_lang_he,
    )

    private val presets = mapOf(
        "ru" to listOf(
            Preset(R.string.voice_bench_preset_normal, R.string.voice_bench_text_ru_normal),
            Preset(R.string.voice_bench_preset_question, R.string.voice_bench_text_ru_question),
            Preset(R.string.voice_bench_preset_emotional, R.string.voice_bench_text_ru_emotional),
            Preset(R.string.voice_bench_preset_long, R.string.voice_bench_text_ru_long),
        ),
        "en" to listOf(
            Preset(R.string.voice_bench_preset_normal, R.string.voice_bench_text_en_normal),
            Preset(R.string.voice_bench_preset_question, R.string.voice_bench_text_en_question),
        ),
        "he" to listOf(
            Preset(R.string.voice_bench_preset_normal, R.string.voice_bench_text_he_normal),
            Preset(R.string.voice_bench_preset_long, R.string.voice_bench_text_he_long),
        ),
    )

    private var currentPresets: List<Preset> = emptyList()

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording() else Toast.makeText(this, R.string.voice_bench_mic_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceBenchmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_voice_benchmark)

        recorder = ReferenceVoiceRecorder(this)
        resultsDir = File(filesDir, "voice_benchmark/results").also { it.mkdirs() }
        runner = VoiceBenchmarkRunner(
            listOf(
                AndroidTtsBenchmarkEngine(this, resultsDir),
                Qwen3TtsBenchmarkEngine(Qwen3TtsBenchmarkEngine.Size.SMALL),
                Qwen3TtsBenchmarkEngine(Qwen3TtsBenchmarkEngine.Size.LARGE),
                ChatterboxBenchmarkEngine(),
            ),
        )

        setupReference()
        setupTest()
        setupEngines()

        binding.voiceBenchGenerateButton.setOnClickListener { runSelected() }
        binding.voiceBenchCancelButton.setOnClickListener { runJob?.cancel() }
        binding.voiceBenchClearButton.setOnClickListener { clearResults() }
        renderRunning(false)
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recorder.isRecording) recorder.stop()
        stopPlayback()
    }

    // ── reference voice ───────────────────────────────────────────────────

    private fun setupReference() {
        binding.voiceBenchRecordButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.voiceBenchStopButton.setOnClickListener { recorder.stop() }
        binding.voiceBenchPlayRefButton.setOnClickListener {
            togglePlayback(recorder.file, binding.voiceBenchPlayRefButton)
        }
        renderReference()
    }

    private fun startRecording() {
        stopPlayback()
        val started = recorder.start(
            onLevel = { level -> runOnUiThread { binding.voiceBenchLevel.progress = (level * 100).toInt() } },
            onElapsed = { ms ->
                runOnUiThread {
                    binding.voiceBenchRefInfo.text = getString(R.string.voice_bench_ref_recording, ms / 1000.0)
                }
            },
            onFinished = { runOnUiThread { renderReference() } },
        )
        if (!started) {
            Toast.makeText(this, R.string.voice_bench_mic_failed, Toast.LENGTH_LONG).show()
            return
        }
        renderReference()
    }

    private fun renderReference() {
        val recording = recorder.isRecording
        val has = recorder.hasRecording
        binding.voiceBenchRecordButton.isEnabled = !recording
        binding.voiceBenchRecordButton.setText(if (has) R.string.voice_bench_rerecord else R.string.voice_bench_record)
        binding.voiceBenchStopButton.isEnabled = recording
        binding.voiceBenchPlayRefButton.isEnabled = has && !recording
        if (!recording) {
            binding.voiceBenchLevel.progress = 0
            binding.voiceBenchRefInfo.text = if (has) {
                getString(R.string.voice_bench_ref_saved, recorder.durationMs() / 1000.0)
            } else {
                getString(R.string.voice_bench_ref_none)
            }
        }
    }

    // ── test input ────────────────────────────────────────────────────────

    private fun setupTest() {
        binding.voiceBenchLanguageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { getString(it.second) },
        )
        binding.voiceBenchLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onLanguageChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.voiceBenchPresetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Position 0 is always "Custom text": leaves whatever is typed alone.
                currentPresets.getOrNull(position - 1)?.textRes?.let { binding.voiceBenchText.setText(getString(it)) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        onLanguageChanged()
    }

    private fun selectedLanguage(): String = languages[binding.voiceBenchLanguageSpinner.selectedItemPosition.coerceAtLeast(0)].first

    private fun onLanguageChanged() {
        val language = selectedLanguage()
        currentPresets = presets[language].orEmpty()
        val labels = listOf(getString(R.string.voice_bench_preset_custom)) + currentPresets.map { getString(it.labelRes) }
        binding.voiceBenchPresetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        currentPresets.firstOrNull()?.textRes?.let {
            binding.voiceBenchPresetSpinner.setSelection(1, false)
            binding.voiceBenchText.setText(getString(it))
        }
        renderEngineSupport()
    }

    // ── engines ───────────────────────────────────────────────────────────

    private fun setupEngines() {
        for (engine in runner.engines) {
            val check = CheckBox(this).apply {
                text = engine.displayName
                isChecked = true
            }
            engineChecks[engine.id] = check
            binding.voiceBenchEngineList.addView(check)
        }
        renderEngineSupport()
    }

    // The language list is fixed, but what each engine can do with it is not:
    // an engine that doesn't speak the chosen language says so right here
    // (and still runs, so the result card records it as a compatibility test).
    private fun renderEngineSupport() {
        val language = selectedLanguage()
        for (engine in runner.engines) {
            engineChecks[engine.id]?.text = if (language in engine.supportedLanguages) {
                engine.displayName
            } else {
                getString(R.string.voice_bench_engine_unsupported, engine.displayName)
            }
        }
    }

    // ── running ───────────────────────────────────────────────────────────

    private fun buildRequest(): VoiceBenchmarkRunner.Request? {
        val text = binding.voiceBenchText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.voice_bench_no_text, Toast.LENGTH_SHORT).show()
            return null
        }
        return VoiceBenchmarkRunner.Request(
            text = text,
            language = selectedLanguage(),
            referenceAudio = if (recorder.hasRecording) recorder.file else null,
            referenceText = binding.voiceBenchTranscript.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun runSelected() {
        val ids = runner.engines.map { it.id }.filter { engineChecks[it]?.isChecked == true }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.voice_bench_no_engines, Toast.LENGTH_SHORT).show()
            return
        }
        val request = buildRequest() ?: return
        stopPlayback()
        launchRun {
            runner.runAll(ids, request, onStarted = { markRunning(it) }, onFinished = { showResult(it) })
        }
    }

    private fun repeat(engineId: String) {
        val request = buildRequest() ?: return
        stopPlayback()
        launchRun {
            markRunning(engineId)
            showResult(runner.runOne(engineId, request))
        }
    }

    private fun launchRun(block: suspend () -> Unit) {
        if (runJob?.isActive == true) return
        renderRunning(true)
        runJob = lifecycleScope.launch {
            try {
                block()
                binding.voiceBenchStatus.text = getString(R.string.voice_bench_done)
            } catch (e: CancellationException) {
                binding.voiceBenchStatus.text = getString(R.string.voice_bench_cancelled)
                resultViews.forEach { (id, item) ->
                    if (results[id] == null) item.voiceBenchResultDetails.text = getString(R.string.voice_bench_cancelled)
                }
                throw e
            } finally {
                renderRunning(false)
            }
        }
    }

    private fun renderRunning(running: Boolean) {
        binding.voiceBenchGenerateButton.isEnabled = !running
        binding.voiceBenchCancelButton.isEnabled = running
        binding.voiceBenchClearButton.isEnabled = !running
        resultViews.values.forEach { it.voiceBenchResultRepeat.isEnabled = !running }
    }

    // ── results ───────────────────────────────────────────────────────────

    private fun engineName(id: String) = runner.engines.firstOrNull { it.id == id }?.displayName ?: id

    private fun card(engineId: String): ItemVoiceBenchmarkResultBinding =
        resultViews.getOrPut(engineId) {
            ItemVoiceBenchmarkResultBinding.inflate(layoutInflater, binding.voiceBenchResults, false).also { item ->
                item.voiceBenchResultTitle.text = engineName(engineId)
                item.voiceBenchResultPlay.setOnClickListener {
                    results[engineId]?.audioFile?.let { togglePlayback(it, item.voiceBenchResultPlay) }
                }
                item.voiceBenchResultRepeat.setOnClickListener { repeat(engineId) }
                binding.voiceBenchResults.addView(item.root)
            }
        }

    private fun markRunning(engineId: String) {
        val item = card(engineId)
        results.remove(engineId)?.audioFile?.delete()
        item.voiceBenchResultDetails.text = getString(R.string.voice_bench_running, engineName(engineId))
        item.voiceBenchResultPlay.isEnabled = false
        item.voiceBenchResultRepeat.isEnabled = false
        binding.voiceBenchStatus.text = getString(R.string.voice_bench_running, engineName(engineId))
    }

    private fun showResult(result: VoiceBenchmarkResult) {
        results[result.engineId] = result
        val item = card(result.engineId)
        val language = languages.first { it.first == selectedLanguage() }.second
        item.voiceBenchResultTitle.text =
            getString(R.string.voice_bench_result_meta, engineName(result.engineId), getString(language))
        item.voiceBenchResultDetails.text = when {
            result.success -> buildString {
                append(
                    getString(
                        R.string.voice_bench_result_timing,
                        (result.generationMs ?: 0L) / 1000.0,
                        (result.audioDurationMs ?: 0L) / 1000.0,
                        result.rtf ?: 0.0,
                    ),
                )
                result.loadMs?.let { append('\n').append(getString(R.string.voice_bench_result_load, it / 1000.0)) }
            }
            result.status == VoiceBenchmarkStatus.NOT_INSTALLED -> getString(R.string.voice_bench_status_not_installed)
            result.status == VoiceBenchmarkStatus.UNSUPPORTED_LANGUAGE -> getString(R.string.voice_bench_status_unsupported)
            else -> getString(R.string.voice_bench_status_error, result.error ?: "")
        }
        item.voiceBenchResultPlay.isEnabled = result.success && result.audioFile != null
        item.voiceBenchResultRepeat.isEnabled = runJob?.isActive != true
    }

    private fun clearResults() {
        stopPlayback()
        results.values.forEach { it.audioFile?.delete() }
        results.clear()
        resultViews.clear()
        binding.voiceBenchResults.removeAllViews()
        binding.voiceBenchStatus.text = ""
    }

    // ── playback ──────────────────────────────────────────────────────────

    private fun togglePlayback(file: File, button: com.google.android.material.button.MaterialButton) {
        val wasPlayingThis = playingButton === button
        stopPlayback()
        if (wasPlayingThis) return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingButton = button
            button.setText(R.string.voice_bench_stop_playing)
        } catch (e: Exception) {
            stopPlayback()
            Toast.makeText(this, e.message ?: e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPlayback() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        playingButton?.setText(R.string.voice_bench_play)
        playingButton = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        UtilityMenu.inflate(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        UtilityMenu.handle(this, item.itemId) || super.onOptionsItemSelected(item)
}
