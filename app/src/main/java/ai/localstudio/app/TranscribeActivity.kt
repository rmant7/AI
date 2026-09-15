package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityTranscribeBinding
import ai.localstudio.app.databinding.ItemTranscribeResultBinding
import ai.localstudio.app.vosk.VoskModelStore
import ai.localstudio.app.whisper.MediaFileUtils
import ai.localstudio.app.whisper.MicrophoneAudioSource
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.speech.StreamingRoutingSession
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Test harness for the whisper.cpp vertical slice
 * (docs/13-asr-pipeline-migration.md): pick a file or a folder, transcribe
 * it through [ai.localstudio.app.whisper.WhisperFileTranscriber] (which loads
 * the model once, via [ai.localstudio.app.AppContainer.whisperFileTranscriber],
 * and reuses it across every file), watch segments arrive before the file
 * finishes decoding, save each result as it completes. Also carries a
 * "LIVE MIC" section (Phase 3) driving
 * [ai.localstudio.app.whisper.WhisperCppMicSession] — a genuinely
 * incremental [ai.localstudio.core.runtime.SpeechModelHandle.startStreaming]
 * session fed by the microphone, independent of the file/folder controls
 * above it and of [ChatActivity]'s own (currently hidden) mic button, which
 * this class does not touch.
 *
 * Deliberately not wired through [ai.localstudio.core.engine.Orchestrator] —
 * this is a way to exercise [ai.localstudio.app.whisper.WhisperCppRuntime]/
 * [ai.localstudio.app.whisper.WhisperCppSpeechModel] directly, the same way
 * [ChatActivity]'s mic button already talks to `WhisperEngine` directly
 * rather than through the router.
 */
class TranscribeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscribeBinding
    private lateinit var container: AppContainer
    private val adapter = ResultAdapter()
    private val results = mutableListOf<Result>()
    private var job: Job? = null

    /** The source clip currently loaded for playback, so a row can tell whether it's the one showing a pause icon. */
    private var playingUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null

    /** Phase 3 test harness state — see the "LIVE MIC" section in activity_transcribe.xml and WhisperCppMicSession's own doc comment. */
    private var micActive = false

    /** Vosk ASR spike (docs/14-vosk-spike.md) — see the "LIVE MIC — VOSK" section in activity_transcribe.xml and VoskSpeechRecognizer's own doc comment. Independent of [micActive]: the two sections never run at once (each start stops the other), but are otherwise unrelated code paths. */
    private var voskActive = false

    /** docs/15-speech-routing.md's experimental language-routed mic — see the "LIVE MIC — LANGUAGE ROUTER" section in activity_transcribe.xml. Also mutually exclusive with the other two mic sections (same reasoning: one physical microphone). */
    private var routerActive = false
    private var routerSession: StreamingRoutingSession? = null

    /** Unlike WhisperCppMicSession/VoskSpeechRecognizer, the router session has no built-in mic-read loop of its own to cancel on stop — this Activity drives AudioSource.stream itself (see startRouter), so it must track and cancel that job itself too. */
    private var routerMicJob: Job? = null

    /** Set by whichever of [onMicToggleClicked]/[onVoskToggleClicked] triggered the permission request, so [requestMicPermission]'s callback starts the right engine once granted. */
    private var pendingMicStart: (() -> Unit)? = null

    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val start = pendingMicStart
        pendingMicStart = null
        if (granted) start?.invoke() else Toast.makeText(this, R.string.chat_mic_permission, Toast.LENGTH_SHORT).show()
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        results.clear()
        val name = displayName(uri)
        results += Result(uri, name)
        binding.transcribeSourceText.text = name
        render()
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tree = DocumentFile.fromTreeUri(this, uri)
        val files = tree?.let { MediaFileUtils.listMediaFilesRecursively(it) }.orEmpty()
        results.clear()
        files.forEach { file -> results += Result(file.uri, file.name ?: file.uri.toString()) }
        binding.transcribeSourceText.text = getString(R.string.transcribe_folder_found, files.size)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranscribeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)

        binding.transcribeResults.layoutManager = LinearLayoutManager(this)
        binding.transcribeResults.adapter = adapter
        // wrap_content + no nested scrolling: the outer ScrollView
        // (activity_transcribe.xml) is the only thing that scrolls now, so
        // this list lays out to its full content height instead of fighting
        // the parent for a bounded scrollable region.
        binding.transcribeResults.isNestedScrollingEnabled = false
        // maxHeight (see activity_transcribe.xml) only bounds the box —
        // without this, text past that height was clipped with no way to
        // reach it at all.
        binding.micTranscriptText.movementMethod = ScrollingMovementMethod()
        binding.voskTranscriptText.movementMethod = ScrollingMovementMethod()
        binding.routerTranscriptText.movementMethod = ScrollingMovementMethod()

        binding.pickFileButton.setOnClickListener { pickFileLauncher.launch(arrayOf("audio/*", "video/*")) }
        binding.pickFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.openVoiceModelsButton.setOnClickListener { startActivity(ModelsActivity.intent(this, ModelsActivity.Category.VOICE)) }
        binding.transcribeStartButton.setOnClickListener { start() }
        binding.transcribeStopButton.setOnClickListener { stop() }
        binding.micToggleButton.setOnClickListener { onMicToggleClicked() }
        binding.voskToggleButton.setOnClickListener { onVoskToggleClicked() }
        binding.routerToggleButton.setOnClickListener { onRouterToggleClicked() }

        render()
        renderMicState()
        renderVoskState()
        renderRouterState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        // Audio playing on from a screen the user has already left (Home,
        // app switcher, the Voice model picker) is a leak, not a feature —
        // stop it here rather than waiting for onDestroy, which a mere
        // background/foreground cycle never reaches. Same reasoning for a
        // live mic recording still listening into the background.
        stopPlayback()
        if (micActive) stopMic()
        if (voskActive) stopVosk()
        if (routerActive) stopRouter()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Batch lifecycle, not per-file: the model stays loaded across every
        // file in this run and is only released when this screen is actually
        // done, matching docs/13-asr-pipeline-migration.md's "load once" rule.
        // (Also freed under memory pressure regardless — see
        // AppContainer.releaseWhisperEngines — for the case where the
        // screen is merely backgrounded, not destroyed.)
        //
        // Off the main thread: release() blocks until any in-flight
        // transcription actually unwinds (see its own doc comment) — fine on
        // a background coroutine, an ANR risk called straight from onDestroy.
        CoroutineScope(Dispatchers.IO).launch {
            container.whisperFileTranscriber.release()
            container.whisperMicSession.release()
            container.voskRecognizer.release()
        }
    }

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

    private fun start() {
        // Same resolution as ChatActivity's mic path: the model the user
        // actually picked on the Models > Voice tab, falling back to the
        // largest installed only if that one isn't (or was never) chosen.
        // Plain installedSeed() ignored Settings.whisperModelId entirely,
        // which is why this screen kept using whichever model happened to
        // be installed first (usually Tiny, the auto-downloaded default) no
        // matter what was selected — Tiny's transcription quality on real
        // speech is exactly what that looks like.
        val seed = container.whisperStore.installedSeed(container.settings.whisperModelId)
        if (seed == null) {
            Toast.makeText(this, R.string.transcribe_no_model, Toast.LENGTH_LONG).show()
            return
        }
        if (results.isEmpty() || job?.isActive == true) return

        setRunning(true)
        job = lifecycleScope.launch {
            try {
                for (result in results) {
                    if (result.status == Status.DONE) continue
                    result.status = Status.RUNNING
                    result.text = ""
                    render()
                    runOne(result, seed)
                }
            } finally {
                setRunning(false)
            }
        }
    }

    private suspend fun runOne(result: Result, seed: WhisperModelSeed) {
        try {
            val transcript = container.whisperFileTranscriber.transcribe(result.uri, seed, language = null) { segment: TranscriptSegment ->
                // Fires as each segment is finalized, before the rest of the
                // file has decoded — the concrete, on-screen version of the
                // vertical slice's acceptance criterion.
                result.text = (result.text + " " + segment.text).trim()
                runOnUiThread { render() }
            }
            val finalText = transcript.text.ifBlank { result.text }
            val savedName = save(result.name, finalText)
            result.text = finalText
            result.status = Status.DONE
            result.savedAs = savedName
        } catch (e: CancellationException) {
            result.status = Status.CANCELLED
            // Whatever segments already arrived via onSegment before Stop was
            // pressed are real transcript, not garbage — discarding them
            // (the old behavior: CANCELLED with no save()) meant a long file
            // stopped partway through showed neither text nor a saved file,
            // even after minutes of real transcription work.
            if (result.text.isNotBlank()) result.savedAs = save(result.name, result.text)
            throw e
        } catch (e: Exception) {
            result.status = Status.ERROR
            result.error = e.message ?: e.toString()
        } finally {
            // Not a plain trailing call: the CancellationException branch
            // above re-throws (it must, to actually cancel the loop in
            // start()) — a render() placed after the try/catch instead of
            // in finally would never run on that path, which is exactly why
            // Stop looked like it did nothing: status/savedAs were updated
            // in memory correctly, but the RecyclerView row was never told
            // to redraw.
            render()
        }
    }

    private fun save(sourceName: String, text: String): String {
        val dir = File(filesDir, "transcripts").apply { mkdirs() }
        val safeName = sourceName.substringBeforeLast('.').ifBlank { "transcript" }
        val out = File(dir, "$safeName.txt")
        out.writeText(text)
        return out.name
    }

    private fun stop() {
        job?.cancel()
        container.whisperFileTranscriber.requestCancel()
    }

    private fun onMicToggleClicked() {
        if (micActive) {
            stopMic()
            return
        }
        if (voskActive) stopVosk()
        if (routerActive) stopRouter()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMic()
        } else {
            pendingMicStart = ::startMic
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMic() {
        val seed = container.whisperStore.installedSeed(container.settings.whisperModelId)
        if (seed == null) {
            Toast.makeText(this, R.string.transcribe_no_model, Toast.LENGTH_LONG).show()
            return
        }
        micActive = true
        renderMicState()
        binding.micTranscriptText.text = ""
        binding.micTranscriptText.visibility = View.VISIBLE
        lifecycleScope.launch {
            val segments = container.whisperMicSession.start(seed, MicrophoneAudioSource(), language = null)
            var settledText = ""
            var currentUtteranceStartMs = -1L
            // Suspends for as long as the session is active — completes when
            // WhisperCppMicSession.finish()/cancel() closes its underlying
            // channel (see StreamingSpeechSession's own contract).
            //
            // Every ~2s re-transcription of the still-growing utterance
            // (docs/12-audio.md's sliding-window policy — see
            // WhisperCppSpeechModel.startStreaming) arrives on this same
            // Flow as its own TranscriptSegment, not just the final one —
            // appending each of those would render as steadily duplicating
            // garbage ("hello hello how hello how are…") instead of a
            // revised line. StreamingSpeechSession carries no explicit
            // partial/final flag, so this relies on the one thing that does
            // distinguish them: every revision of the same utterance keeps
            // the same startMs (see WhisperCppSpeechModel.startStreaming's
            // emit()) — a changed startMs is what "the previous utterance
            // settled, a new one began" actually looks like on this Flow.
            segments.collect { segment ->
                if (segment.startMs != currentUtteranceStartMs) {
                    settledText = binding.micTranscriptText.text?.toString().orEmpty()
                    currentUtteranceStartMs = segment.startMs
                }
                binding.micTranscriptText.text = (settledText + " " + segment.text).trim()
            }
            // The Flow can complete on its own (the session finished/was
            // cancelled from elsewhere, e.g. onPause) without stopMic() ever
            // running — keep the button/status in sync either way. If it
            // stopped because the mic loop actually failed (AudioRecord
            // couldn't init, permission revoked mid-session, ...) rather
            // than a normal finish()/cancel(), say so instead of just
            // silently going idle — see WhisperCppMicSession.lastError's
            // own doc comment for why this is the only way to tell the two
            // apart.
            micActive = false
            renderMicState()
            container.whisperMicSession.lastError?.let { error ->
                showErrorDialog(getString(R.string.transcribe_mic_error, error.message ?: error.toString()))
            }
        }
    }

    private fun stopMic() {
        container.whisperMicSession.finish()
        micActive = false
        renderMicState()
    }

    private fun renderMicState() {
        binding.micToggleButton.text = getString(if (micActive) R.string.transcribe_mic_stop else R.string.transcribe_mic_start)
        binding.micStatusText.text = getString(if (micActive) R.string.transcribe_mic_listening else R.string.transcribe_mic_idle)
    }

    // ── Vosk ASR spike (docs/14-vosk-spike.md) ────────────────────────────

    private fun onVoskToggleClicked() {
        if (voskActive) {
            stopVosk()
            return
        }
        if (micActive) stopMic()
        if (routerActive) stopRouter()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVosk()
        } else {
            pendingMicStart = ::startVosk
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVosk() {
        val seed = VoskModelStore.installedSeed(this, container.settings.voskModelId)
        if (seed == null) {
            Toast.makeText(this, R.string.transcribe_vosk_no_model, Toast.LENGTH_LONG).show()
            return
        }
        val modelDir = VoskModelStore.modelDir(this, seed)
        voskActive = true
        renderVoskState()
        binding.voskTranscriptText.text = ""
        binding.voskTranscriptText.visibility = View.VISIBLE
        lifecycleScope.launch {
            // Model(path)/Recognizer construction throw a checked IOException
            // on a corrupt/partially-extracted model directory — which
            // would otherwise crash the app right here instead of just
            // failing this one start attempt.
            val transcripts = try {
                container.voskRecognizer.start(modelDir.absolutePath, MicrophoneAudioSource())
            } catch (e: Exception) {
                voskActive = false
                renderVoskState()
                showErrorDialog(getString(R.string.transcribe_mic_error, e.message ?: e.toString()))
                return@launch
            }
            // Every emission already carries the full session text so far —
            // see VoskSpeechRecognizer.start's own doc comment for why this
            // needs none of WhisperCppMicSession's startMs-matching.
            transcripts.collect { transcript ->
                binding.voskTranscriptText.text = transcript.text
            }
            voskActive = false
            renderVoskState()
            container.voskRecognizer.lastError?.let { error ->
                showErrorDialog(getString(R.string.transcribe_mic_error, error.message ?: error.toString()))
            }
        }
    }

    private fun stopVosk() {
        container.voskRecognizer.finish()
        voskActive = false
        renderVoskState()
    }

    private fun renderVoskState() {
        binding.voskToggleButton.text = getString(if (voskActive) R.string.transcribe_mic_stop else R.string.transcribe_mic_start)
        binding.voskStatusText.text = getString(if (voskActive) R.string.transcribe_mic_listening else R.string.transcribe_mic_idle)
    }

    // ── Language-routed mic (docs/15-speech-routing.md) ───────────────────

    private fun onRouterToggleClicked() {
        if (routerActive) {
            stopRouter()
            return
        }
        if (micActive) stopMic()
        if (voskActive) stopVosk()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRouter()
        } else {
            pendingMicStart = ::startRouter
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRouter() {
        routerActive = true
        renderRouterState()
        binding.routerTranscriptText.text = ""
        binding.routerTranscriptText.visibility = View.VISIBLE

        val session = container.speechRouter.start()
        routerSession = session

        // The mic-read loop and segment collection both need to run for as
        // long as the session is active; source.stream()'s own suspend
        // callback calls session.acceptAudio directly — same shape as
        // WhisperCppMicSession/VoskSpeechRecognizer's own mic-read loops,
        // except *this* loop lives here, not inside the session — see
        // routerMicJob's own doc comment for why stopRouter must cancel it
        // explicitly rather than relying on session.finish() alone.
        routerMicJob = lifecycleScope.launch {
            try {
                MicrophoneAudioSource().stream { chunk -> session.acceptAudio(chunk) }
                session.finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.cancel()
                showErrorDialog(getString(R.string.transcribe_mic_error, e.message ?: e.toString()))
            }
        }

        lifecycleScope.launch {
            // Every segment already names which language/model produced
            // it — see TranscriptSegment.language/modelId's own doc
            // comment — which is the entire point of this section: seeing
            // the router's own decisions, not just clean text.
            session.segments.collect { segment ->
                val tag = "[${segment.language ?: "?"}][${segment.modelId ?: "?"}]"
                val line = "$tag ${segment.text}"
                binding.routerTranscriptText.text =
                    if (binding.routerTranscriptText.text.isNullOrBlank()) line else "${binding.routerTranscriptText.text}\n$line"
            }
            routerActive = false
            routerSession = null
            renderRouterState()
        }

        // RoutingDecision was already emitted for every LID window since the
        // foundation commit, but nothing ever read it — there was no way to
        // tell "the router looked at 3s of English and decided to stay on
        // Russian" from "the router never even considered switching" without
        // this. Every window, not just switches, so a stuck session is
        // visible as a repeating reason rather than silence.
        lifecycleScope.launch {
            session.decisions.collect { decision ->
                container.appLog.record("ROUTER", decision.reason)
            }
        }
    }

    private fun stopRouter() {
        val session = routerSession
        routerActive = false
        renderRouterState()
        // Cancel the mic-read loop first (it lives here, not inside the
        // session — see routerMicJob's own doc comment), then finish the
        // session itself to flush whatever the active model has buffered.
        routerMicJob?.cancel()
        routerMicJob = null
        if (session != null) lifecycleScope.launch { session.finish() }
    }

    private fun renderRouterState() {
        binding.routerToggleButton.text = getString(if (routerActive) R.string.transcribe_mic_stop else R.string.transcribe_mic_start)
        binding.routerStatusText.text = getString(if (routerActive) R.string.transcribe_mic_listening else R.string.transcribe_mic_idle)
    }

    /**
     * Plays [uri] straight from its own SAF/file source — no decode through
     * [ai.localstudio.app.whisper.MediaCodecAudioSource], deliberately: the
     * point is to hear the *original* clip next to the transcript, not a
     * resampled copy of what whisper.cpp actually received.
     */
    private fun togglePlayback(uri: Uri) {
        if (playingUri == uri) {
            stopPlayback()
            return
        }
        stopPlayback()
        playingUri = uri
        render()
        val player = MediaPlayer()
        try {
            player.setDataSource(this, uri)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { stopPlayback() }
            player.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            player.release()
            Toast.makeText(this, getString(R.string.transcribe_play_failed, e.message ?: e.toString()), Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let { player -> runCatching { player.stop() }; player.release() }
        mediaPlayer = null
        playingUri = null
        render()
    }

    private fun setRunning(running: Boolean) {
        binding.transcribeStartButton.isEnabled = !running && results.isNotEmpty()
        binding.transcribeStopButton.isEnabled = running
        binding.transcribeProgress.visibility = if (running) View.VISIBLE else View.GONE
        binding.pickFileButton.isEnabled = !running
        binding.pickFolderButton.isEnabled = !running
    }

    private fun render() {
        binding.transcribeEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        binding.transcribeResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        binding.transcribeStartButton.isEnabled = results.isNotEmpty() && job?.isActive != true
        adapter.submit(results.toList())
    }

    private enum class Status { PENDING, RUNNING, DONE, ERROR, CANCELLED }

    private class Result(val uri: Uri, val name: String) {
        var status: Status = Status.PENDING
        var text: String = ""
        var error: String? = null
        var savedAs: String? = null
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private var items: List<Result> = emptyList()

        fun submit(next: List<Result>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemTranscribeResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(val binding: ItemTranscribeResultBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(result: Result) {
                binding.resultFileName.text = result.name
                binding.resultStatus.text = when (result.status) {
                    Status.PENDING -> getString(R.string.transcribe_status_pending)
                    Status.RUNNING -> getString(R.string.transcribe_status_running)
                    Status.DONE -> getString(R.string.transcribe_status_done, result.savedAs ?: "")
                    Status.ERROR -> getString(R.string.transcribe_status_error, result.error ?: "")
                    Status.CANCELLED -> result.savedAs?.let { getString(R.string.transcribe_status_cancelled_saved, it) }
                        ?: getString(R.string.transcribe_status_cancelled)
                }
                binding.resultText.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultText.text = result.text
                binding.resultCopyButton.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultCopyButton.setOnClickListener { copyToClipboard(result.text) }
                binding.resultShareButton.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultShareButton.setOnClickListener { shareText(result.text) }

                val isPlaying = playingUri == result.uri
                binding.resultPlayButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                binding.resultPlayButton.contentDescription = getString(if (isPlaying) R.string.transcribe_pause else R.string.transcribe_play)
                binding.resultPlayButton.setOnClickListener { togglePlayback(result.uri) }
            }
        }
    }

    /**
     * The saved .txt lives under this app's own private filesDir — invisible
     * to any file manager, Downloads app, or other app without root. Plain
     * text share (same pattern as ChatActivity.shareChat()) is the actual
     * way out: the system share sheet's own targets (Files, Drive, Telegram,
     * ...) already know how to save that text as a file somewhere the user
     * can reach, without this needing a FileProvider of its own.
     */
    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.transcribe_share)))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label_transcript), text))
        // Android 13+ shows its own "Copied" system toast for clipboard writes;
        // showing this one too would be a redundant second confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A mic-session error used to surface as a Toast — which truncates long
     * text and disappears on its own timer, exactly wrong for something
     * someone might need to actually read in full or copy out (to report it,
     * say). This shows the whole message, selectable, with an explicit Copy
     * button and no auto-dismiss — closed only by the OK button or tapping
     * outside, like any other dialog.
     */
    private fun showErrorDialog(message: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.transcribe_error_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .setNeutralButton(R.string.log_copy) { _, _ -> copyToClipboard(message) }
            .show()
        dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
}
