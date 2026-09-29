package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityVoiceBenchmarkBinding
import ai.localstudio.app.databinding.ItemVoiceBenchmarkResultBinding
import ai.localstudio.app.voicebenchmark.QwenModelState
import ai.localstudio.app.voicebenchmark.QwenTtsModelDescriptor
import ai.localstudio.app.voicebenchmark.QwenTtsModelProvider
import ai.localstudio.app.voicebenchmark.QwenTtsRuntimeManager
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkResult
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkRunner
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkStatus
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkViewModel
import ai.localstudio.app.voicebenchmark.VoiceBenchmarkViewModel.Status
import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Voice benchmark": record a reference voice, pick a language and a phrase,
 * and run every selected engine on exactly the same input, keeping each
 * engine's WAV next to its timing so the voices can be compared by ear.
 *
 * This screen only draws. The work, the results, the recording and the inputs
 * live in [VoiceBenchmarkViewModel], so changing the app language (which
 * recreates the activity) or rotating the phone loses nothing.
 */
class VoiceBenchmarkActivity : AppCompatActivity() {

    private class Preset(val labelRes: Int, val textRes: Int?)

    private lateinit var binding: ActivityVoiceBenchmarkBinding
    private lateinit var vm: VoiceBenchmarkViewModel

    private val engineChecks = LinkedHashMap<String, CheckBox>()
    private val resultViews = LinkedHashMap<String, ItemVoiceBenchmarkResultBinding>()

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
        vm = VoiceBenchmarkViewModel.get(application)
        binding.voiceBenchStatus.apply { minLines = 2; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }

        // The transcript is what the avatar's cloned voice is made from too, so keep it.
        val settings = AppContainer.get(this).settings
        binding.voiceBenchTranscript.setText(settings.voiceReferenceText)
        binding.voiceBenchTranscript.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                settings.voiceReferenceText = s?.toString().orEmpty().trim()
            }
        })

        setupReference()
        setupTest()
        setupEngines()
        setupQwenModel()
        setupQwenTuning()

        binding.voiceBenchGenerateButton.setOnClickListener { runSelected() }
        binding.voiceBenchCancelButton.setOnClickListener { vm.cancel() }
        binding.voiceBenchClearButton.setOnClickListener {
            stopPlayback()
            vm.clearResults()
        }
        vm.inputsInitialized = true

        lifecycleScope.launch { vm.state.collect { render(it) } }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        if (isFinishing) vm.onScreenClosed()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Setting identical text on a TextView still resets its selection and can scroll the page;
    // the state is re-drawn every second while a run is going, so only touch what changed.
    private fun android.widget.TextView.setTextIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
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
        binding.voiceBenchStopButton.setOnClickListener { vm.stopRecording() }
        binding.voiceBenchPlayRefButton.setOnClickListener {
            togglePlayback(vm.recorder.file, binding.voiceBenchPlayRefButton)
        }
    }

    private fun startRecording() {
        stopPlayback()
        if (!vm.startRecording()) Toast.makeText(this, R.string.voice_bench_mic_failed, Toast.LENGTH_LONG).show()
    }

    private fun renderReference(rec: VoiceBenchmarkViewModel.Recording) {
        binding.voiceBenchRecordButton.isEnabled = !rec.recording
        binding.voiceBenchRecordButton.setText(if (rec.hasRecording) R.string.voice_bench_rerecord else R.string.voice_bench_record)
        binding.voiceBenchStopButton.isEnabled = rec.recording
        binding.voiceBenchPlayRefButton.isEnabled = rec.hasRecording && !rec.recording
        binding.voiceBenchLevel.progress = if (rec.recording) (rec.level * 100).toInt() else 0
        binding.voiceBenchRefInfo.setTextIfChanged(when {
            rec.recording -> getString(R.string.voice_bench_ref_recording, rec.elapsedMs / 1000.0)
            rec.hasRecording -> getString(R.string.voice_bench_ref_saved, rec.durationMs / 1000.0)
            else -> getString(R.string.voice_bench_ref_none)
        })
    }

    // ── test input ────────────────────────────────────────────────────────

    private fun setupTest() {
        binding.voiceBenchLanguageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, languages.map { getString(it.second) },
        )
        binding.voiceBenchLanguageSpinner.setSelection(vm.languageIndex, false)
        showPresets()
        binding.voiceBenchText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                vm.text = s?.toString().orEmpty()
            }
        })
        if (vm.inputsInitialized) binding.voiceBenchText.setText(vm.text) else applyPreset(1)

        // A spinner reports its initial selection as if the user had picked it; a
        // restored screen must not answer that by overwriting the text just typed.
        binding.voiceBenchLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == vm.languageIndex) return
                vm.languageIndex = position
                showPresets()
                applyPreset(1)
                renderEngineSupport()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.voiceBenchPresetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == vm.presetIndex) return
                applyPreset(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun selectedLanguage(): String = languages[vm.languageIndex.coerceIn(languages.indices)].first

    // Fills the preset spinner for the chosen language, keeping the remembered choice if it still exists.
    private fun showPresets() {
        currentPresets = presets[selectedLanguage()].orEmpty()
        val labels = listOf(getString(R.string.voice_bench_preset_custom)) + currentPresets.map { getString(it.labelRes) }
        binding.voiceBenchPresetSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.voiceBenchPresetSpinner.setSelection(vm.presetIndex.coerceIn(labels.indices), false)
    }

    // Position 0 is always "Custom text": leaves whatever is typed alone.
    private fun applyPreset(position: Int) {
        val res = currentPresets.getOrNull(position - 1)?.textRes
        vm.presetIndex = if (res != null) position else 0
        binding.voiceBenchPresetSpinner.setSelection(vm.presetIndex, false)
        if (res != null) binding.voiceBenchText.setText(getString(res))
    }

    // ── engines ───────────────────────────────────────────────────────────

    private fun setupEngines() {
        for (engine in vm.runner.engines) {
            val check = CheckBox(this).apply {
                text = engine.displayName
                isChecked = vm.engineChecked[engine.id] ?: true
                setOnCheckedChangeListener { _, checked -> vm.engineChecked[engine.id] = checked }
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
        for (engine in vm.runner.engines) {
            engineChecks[engine.id]?.text = if (language in engine.supportedLanguages) {
                engine.displayName
            } else {
                getString(R.string.voice_bench_engine_unsupported, engine.displayName)
            }
        }
    }

    // ── Qwen model download ───────────────────────────────────────────────

    private fun setupQwenModel() {
        val provider = QwenTtsModelProvider.get(this)
        binding.voiceBenchQwenDownload.setOnClickListener {
            when (provider.state.value) {
                is QwenModelState.Downloading -> provider.cancelDownload()
                QwenModelState.Ready -> Unit
                else -> NetworkPolicy.confirmIfNeeded(this, AppContainer.get(this).settings) { provider.download() }
            }
        }
        binding.voiceBenchQwenDelete.setOnClickListener { provider.delete() }
        lifecycleScope.launch {
            provider.state.collect { state ->
                binding.voiceBenchQwenModelStatus.text = when (state) {
                    QwenModelState.NotDownloaded -> getString(
                        R.string.voice_bench_qwen_not_downloaded, QwenTtsModelDescriptor.totalExpectedBytes / 1e9,
                    )
                    is QwenModelState.Downloading ->
                        getString(R.string.voice_bench_qwen_downloading, (state.fraction * 100).toInt())
                    QwenModelState.Ready -> getString(R.string.voice_bench_qwen_ready)
                    is QwenModelState.Failed -> getString(R.string.voice_bench_qwen_failed, state.message)
                }
                val downloading = state is QwenModelState.Downloading
                binding.voiceBenchQwenDownload.isEnabled = state !is QwenModelState.Ready
                binding.voiceBenchQwenDownload.setText(
                    if (downloading) R.string.voice_bench_qwen_cancel_download else R.string.voice_bench_qwen_download,
                )
                binding.voiceBenchQwenDelete.isEnabled = !downloading && state is QwenModelState.Ready
            }
        }
    }

    // ── Qwen diagnostics ──────────────────────────────────────────────────

    private fun setupQwenTuning() {
        binding.voiceBenchQwenTrim.isChecked = vm.trimReference
        QwenTtsRuntimeManager.referenceMaxSeconds = if (vm.trimReference) 6.0 else null
        binding.voiceBenchQwenTrim.setOnCheckedChangeListener { _, checked ->
            vm.trimReference = checked
            QwenTtsRuntimeManager.referenceMaxSeconds = if (checked) 6.0 else null
        }
        val choices = listOf<Int?>(null, 1, 2, 4, 6, 8)
        val labels = choices.map { n ->
            if (n == null) getString(R.string.voice_bench_qwen_threads_auto, QwenTtsRuntimeManager.defaultThreads())
            else getString(R.string.voice_bench_qwen_threads_n, n)
        }
        binding.voiceBenchQwenThreads.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.voiceBenchQwenThreads.setSelection(vm.threadsIndex.coerceIn(choices.indices), false)
        QwenTtsRuntimeManager.threadsOverride = choices[vm.threadsIndex.coerceIn(choices.indices)]
        binding.voiceBenchQwenThreads.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                vm.threadsIndex = position
                QwenTtsRuntimeManager.threadsOverride = choices[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        // chunk ms to vocoder left-context ms; "whole utterance" is one chunk longer than any answer.
        val streaming = listOf(1000 to 2000, 3000 to 500, 8000 to 500, 600_000 to 0)
        val streamLabels = listOf(
            R.string.voice_bench_qwen_stream_0, R.string.voice_bench_qwen_stream_1,
            R.string.voice_bench_qwen_stream_2, R.string.voice_bench_qwen_stream_3,
        ).map { getString(it) }
        binding.voiceBenchQwenStreaming.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, streamLabels)
        binding.voiceBenchQwenStreaming.setSelection(vm.streamingIndex.coerceIn(streaming.indices), false)
        streaming[vm.streamingIndex.coerceIn(streaming.indices)].let {
            QwenTtsRuntimeManager.streamingChunkMs = it.first
            QwenTtsRuntimeManager.streamingLeftMs = it.second
        }
        binding.voiceBenchQwenStreaming.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                vm.streamingIndex = position
                QwenTtsRuntimeManager.streamingChunkMs = streaming[position].first
                QwenTtsRuntimeManager.streamingLeftMs = streaming[position].second
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.voiceBenchQwenProfile.setOnClickListener { runQwenProfile() }
    }

    // Two runs of the same voice: the first from a cold start (model unloaded,
    // voice cache cleared), the second with the model loaded and the voice
    // prepared but a new text — the difference between them is the one-off cost.
    private fun runQwenProfile() {
        val reference = if (vm.recorder.hasRecording) vm.recorder.file else null
        val transcript = binding.voiceBenchTranscript.text?.toString()?.trim().orEmpty()
        if (reference == null) {
            Toast.makeText(this, R.string.voice_bench_qwen_profile_need_recording, Toast.LENGTH_SHORT).show()
            return
        }
        if (transcript.isEmpty()) {
            Toast.makeText(this, R.string.voice_bench_qwen_profile_need_transcript, Toast.LENGTH_SHORT).show()
            return
        }
        val language = selectedLanguage()
        val texts = when (language) {
            "ru" -> listOf(R.string.voice_bench_profile_text_ru_a, R.string.voice_bench_profile_text_ru_b)
            "en" -> listOf(R.string.voice_bench_profile_text_en_a, R.string.voice_bench_profile_text_en_b)
            else -> {
                Toast.makeText(this, R.string.voice_bench_qwen_profile_language, Toast.LENGTH_SHORT).show()
                return
            }
        }.map { getString(it) }
        val labels = texts.indices.map { index ->
            getString(if (index == 0) R.string.voice_bench_profile_run_cold else R.string.voice_bench_profile_run_warm, index + 1)
        }
        stopPlayback()
        vm.runQwenProfile({ text -> VoiceBenchmarkRunner.Request(text, language, reference, transcript) }, texts, labels)
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
            referenceAudio = if (vm.recorder.hasRecording) vm.recorder.file else null,
            referenceText = binding.voiceBenchTranscript.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun runSelected() {
        val ids = vm.runner.engines.map { it.id }.filter { engineChecks[it]?.isChecked == true }
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.voice_bench_no_engines, Toast.LENGTH_SHORT).show()
            return
        }
        val request = buildRequest() ?: return
        stopPlayback()
        vm.runSelected(ids, request)
    }

    private fun repeat(engineId: String) {
        val request = buildRequest() ?: return
        stopPlayback()
        vm.repeat(engineId, request)
    }

    // ── drawing the ViewModel's state ─────────────────────────────────────

    private fun render(ui: VoiceBenchmarkViewModel.Ui) {
        renderReference(ui.recording)
        // A run takes minutes: a screen that turns off lets Android throttle or freeze the app.
        if (ui.running) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.voiceBenchGenerateButton.isEnabled = !ui.running
        binding.voiceBenchCancelButton.isEnabled = ui.running
        binding.voiceBenchClearButton.isEnabled = !ui.running
        binding.voiceBenchProfileOutput.setTextIfChanged(ui.profileReport)
        binding.voiceBenchStatus.setTextIfChanged(when (val s = ui.status) {
            Status.Idle -> ""
            is Status.Running -> getString(R.string.voice_bench_running, s.engineName)
            Status.Done -> getString(R.string.voice_bench_done)
            Status.Cancelled -> getString(R.string.voice_bench_cancelled)
            is Status.Live -> getString(R.string.voice_bench_live, s.audioMs / 1000.0, s.elapsedMs / 1000.0, s.elapsedMs.toDouble() / s.audioMs)
            is Status.Native -> s.line
        })

        val ids = (ui.results.keys + ui.runningIds).toSet()
        resultViews.keys.filter { it !in ids }.forEach { id ->
            resultViews.remove(id)?.let { binding.voiceBenchResults.removeView(it.root) }
        }
        for (id in ids) {
            val item = card(id)
            val result = ui.results[id]
            when {
                id in ui.runningIds -> {
                    item.voiceBenchResultTitle.setTextIfChanged(vm.engineName(id))
                    item.voiceBenchResultDetails.setTextIfChanged(getString(R.string.voice_bench_running, vm.engineName(id)))
                    item.voiceBenchResultLog.visibility = View.GONE
                    setActionsEnabled(item, playable = false)
                }
                result != null -> showResult(item, result, !ui.running)
                else -> {
                    item.voiceBenchResultDetails.setTextIfChanged(getString(R.string.voice_bench_cancelled))
                    setActionsEnabled(item, playable = false)
                }
            }
        }
    }

    private fun setActionsEnabled(item: ItemVoiceBenchmarkResultBinding, playable: Boolean, repeatable: Boolean = false) {
        item.voiceBenchResultPlay.isEnabled = playable
        item.voiceBenchResultSave.isEnabled = playable
        item.voiceBenchResultShare.isEnabled = playable
        item.voiceBenchResultRepeat.isEnabled = repeatable
    }

    private fun card(engineId: String): ItemVoiceBenchmarkResultBinding =
        resultViews.getOrPut(engineId) {
            ItemVoiceBenchmarkResultBinding.inflate(layoutInflater, binding.voiceBenchResults, false).also { item ->
                item.voiceBenchResultTitle.text = vm.engineName(engineId)
                item.voiceBenchResultPlay.setOnClickListener {
                    audioOf(engineId)?.let { togglePlayback(it, item.voiceBenchResultPlay) }
                }
                item.voiceBenchResultSave.setOnClickListener { audioOf(engineId)?.let { save(engineId, it) } }
                item.voiceBenchResultShare.setOnClickListener { audioOf(engineId)?.let { share(it) } }
                item.voiceBenchResultRepeat.setOnClickListener { repeat(engineId) }
                binding.voiceBenchResults.addView(item.root)
            }
        }

    private fun audioOf(engineId: String): File? =
        vm.state.value.results[engineId]?.audioFile?.takeIf { it.exists() }

    private fun showResult(item: ItemVoiceBenchmarkResultBinding, result: VoiceBenchmarkResult, idle: Boolean) {
        val language = languages.firstOrNull { it.first == (result.language ?: selectedLanguage()) }?.second
        item.voiceBenchResultTitle.setTextIfChanged(if (language != null) {
            getString(R.string.voice_bench_result_meta, vm.engineName(result.engineId), getString(language))
        } else {
            vm.engineName(result.engineId)
        })
        item.voiceBenchResultDetails.setTextIfChanged(when {
            result.success -> resultLines(result).joinToString("\n")
            result.status == VoiceBenchmarkStatus.NOT_INSTALLED -> getString(R.string.voice_bench_status_not_installed)
            result.status == VoiceBenchmarkStatus.MODEL_NOT_DOWNLOADED -> getString(R.string.voice_bench_status_model_missing)
            result.status == VoiceBenchmarkStatus.UNSUPPORTED_DEVICE -> getString(R.string.voice_bench_status_unsupported_device)
            result.status == VoiceBenchmarkStatus.UNSUPPORTED_LANGUAGE -> getString(R.string.voice_bench_status_unsupported)
            else -> getString(R.string.voice_bench_status_error, result.error ?: "")
        })
        item.voiceBenchResultLog.setTextIfChanged(result.details.orEmpty())
        item.voiceBenchResultLog.visibility = if (result.details.isNullOrBlank()) View.GONE else View.VISIBLE
        setActionsEnabled(item, playable = result.success && result.audioFile?.exists() == true, repeatable = idle)
    }

    // Load and voice preparation come first and are reported apart from
    // generation: generation time and RTF cover neither.
    private fun resultLines(result: VoiceBenchmarkResult): List<String> = buildList {
        result.loadMs?.let { add(getString(R.string.voice_bench_result_load, it / 1000.0)) }
        result.voicePrepMs?.let {
            add(if (it == 0L) getString(R.string.voice_bench_result_prep_reused) else getString(R.string.voice_bench_result_prep, it / 1000.0))
        }
        add(
            getString(
                R.string.voice_bench_result_timing,
                (result.generationMs ?: 0L) / 1000.0,
                (result.audioDurationMs ?: 0L) / 1000.0,
                result.rtf ?: 0.0,
            ),
        )
        result.firstAudioMs?.let { add(getString(R.string.voice_bench_result_first, it / 1000.0)) }
        val after = result.memoryAfterMb
        val before = result.memoryBeforeMb
        val free = result.availRamMb
        if (before != null && after != null && free != null) add(getString(R.string.voice_bench_result_memory, before, after, free))
    }

    // ── saving and sharing the audio ──────────────────────────────────────

    private fun exportName(engineId: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        return "voice_${engineId.replace(Regex("[^A-Za-z0-9._-]"), "_")}_$stamp.wav"
    }

    // API 29+: copied into the public Downloads folder through MediaStore, which
    // needs no storage permission. Older phones get the share sheet instead.
    private fun save(engineId: String, file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            share(file)
            return
        }
        val name = exportName(engineId)
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/LocalAIStudio")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = applicationContext.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("MediaStore refused the file")
                    try {
                        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }.exceptionOrNull()
            }
            Toast.makeText(
                this@VoiceBenchmarkActivity,
                if (failure == null) getString(R.string.voice_bench_saved, name)
                else getString(R.string.voice_bench_save_failed, failure.message ?: failure.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.voice_bench_share)))
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
