package ai.localstudio.app

import ai.localstudio.app.benchmark.BenchmarkUiState
import ai.localstudio.app.databinding.ActivityTranscribeBinding
import ai.localstudio.app.databinding.ItemTranscribeResultBinding
import ai.localstudio.app.vosk.VoskModelStore
import ai.localstudio.app.whisper.MediaFileUtils
import ai.localstudio.app.whisper.MicrophoneAudioSource
import ai.localstudio.app.whisper.TranscriptionResult
import ai.localstudio.app.whisper.TranscriptionStatus
import ai.localstudio.app.whisper.WarmupSample
import ai.localstudio.core.speech.StreamingRoutingSession
import ai.localstudio.core.util.describeForUser
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Test harness for the whisper.cpp vertical slice
 * (docs/13-asr-pipeline-migration.md): pick a file or a folder, transcribe
 * it through [ai.localstudio.app.whisper.WhisperFileTranscriber] (which
 * loads the model once, via [ai.localstudio.app.AppContainer.whisperFileTranscriber],
 * and reuses it across every file), watch segments arrive before the file
 * finishes decoding, save each result as it completes. The actual batch
 * job lives in [ai.localstudio.app.AppContainer.fileTranscriptionRunner],
 * not this Activity — see that class's own doc comment for why: this
 * screen only observes it and renders whatever it reports, so the run
 * itself survives navigating away and back, or the Activity being
 * recreated outright. Also carries a "LIVE MIC" section (Phase 3) driving
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

    /** The source clip currently loaded into the shared player bar (see selectAndPlay). */
    private var playingUri: Uri? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playerTickerJob: Job? = null

    /** True while the user has a finger on playerSeekBar — the ticker must not fight a drag by resetting progress out from under it. */
    private var playerSeekBarDragging = false

    /** Phase 3 test harness state — see the "LIVE MIC" section in activity_transcribe.xml and WhisperCppMicSession's own doc comment. */
    private var micActive = false

    /** Vosk ASR spike (docs/14-vosk-spike.md) — see the "LIVE MIC — VOSK" section in activity_transcribe.xml and VoskSpeechRecognizer's own doc comment. Independent of [micActive]: the two sections never run at once (each start stops the other), but are otherwise unrelated code paths. */
    private var voskActive = false

    /** docs/15-speech-routing.md's experimental language-routed mic — see the "LIVE MIC — LANGUAGE ROUTER" section in activity_transcribe.xml. Also mutually exclusive with the other two mic sections (same reasoning: one physical microphone). */
    private var routerActive = false
    private var routerSession: StreamingRoutingSession? = null

    /** Unlike WhisperCppMicSession/VoskSpeechRecognizer, the router session has no built-in mic-read loop of its own to cancel on stop — this Activity drives AudioSource.stream itself (see startRouter), so it must track and cancel that job itself too. */
    private var routerMicJob: Job? = null

    /**
     * One line per utterance, not one line per emission: a model's own
     * StreamingSpeechSession re-emits the *same* utterance repeatedly while
     * it is still growing (every revision keeping the same startMs — see
     * TranscribeActivity's other mic sections for the same contract), and
     * the router forwards every one of those onward unchanged. Appending
     * each as a new line (the original code) meant a single sentence being
     * recognized turned into a wall of near-duplicate lines that blew past
     * routerTranscriptText's height well before a second language ever got
     * a chance to appear on screen — indistinguishable from "the router
     * only transcribes English" if you never scrolled past it. Keyed by
     * (modelId, startMs) rather than just startMs, since a model switch can
     * itself land on the same startMs a moment apart.
     */
    private var routerLines = mutableListOf<String>()
    private var routerCurrentLineKey: String? = null

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
        val name = displayName(uri)
        container.fileTranscriptionRunner.setSource(listOf(TranscriptionResult(uri, name)))
        binding.transcribeSourceText.text = name
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tree = DocumentFile.fromTreeUri(this, uri)
        // listMediaFilesRecursively is a plain synchronous walk over many
        // ContentResolver/SAF calls — on a real folder (reported: ~500
        // files) that is easily several seconds of work, and this callback
        // runs on the main thread, so calling it in-line here froze the
        // whole screen (reported as a black screen) for exactly that long.
        binding.transcribeProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { tree?.let { MediaFileUtils.listMediaFilesRecursively(it) }.orEmpty() }
            container.fileTranscriptionRunner.setSource(files.map { file -> TranscriptionResult(file.uri, file.name ?: file.uri.toString()) })
            binding.transcribeSourceText.text = getString(R.string.transcribe_folder_found, files.size)
            binding.transcribeProgress.visibility = View.GONE
        }
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

        binding.pickFileButton.setOnClickListener { pickFileLauncher.launch(arrayOf("audio/*", "video/*")) }
        binding.pickFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.openVoiceModelsButton.setOnClickListener { startActivity(ModelsActivity.intent(this, ModelsActivity.Category.VOICE)) }
        binding.transcribeStartButton.setOnClickListener { start() }
        binding.transcribeStopButton.setOnClickListener { stop() }
        binding.micToggleButton.setOnClickListener { onMicToggleClicked() }
        binding.voskToggleButton.setOnClickListener { onVoskToggleClicked() }
        binding.routerToggleButton.setOnClickListener { onRouterToggleClicked() }
        binding.playerPlayPauseButton.setOnClickListener { togglePlayerPlayPause() }
        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.playerTimeText.text = formatPlayerTime(progress, mediaPlayer?.duration ?: 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                playerSeekBarDragging = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                playerSeekBarDragging = false
                mediaPlayer?.seekTo(seekBar.progress)
            }
        })

        // The batch job itself lives in AppContainer.fileTranscriptionRunner
        // now, not this Activity — see that class's own doc comment for why
        // (Activity recreation under memory pressure used to silently wipe
        // an in-progress transcription). This is purely an observer: every
        // (results, running) pair fully determines what the screen shows.
        lifecycleScope.launch {
            combine(container.fileTranscriptionRunner.results, container.fileTranscriptionRunner.running) { results, running -> results to running }
                .collect { (results, running) -> renderResults(results, running) }
        }
        renderMicState()
        renderVoskState()
        renderRouterState()
        warmUpSelectedModel()
    }

    /**
     * Fires as soon as this screen opens, silently, so the cold-start cost
     * (mmap page faults, CPU governor ramp-up — see [ai.localstudio.app.whisper.WarmupSample]'s
     * own doc comment) is paid in the background before the user has even
     * picked a file, instead of visibly inflating the first real file's own
     * transcription time. Reuses [AppContainer.whisperFileTranscriber]
     * directly — [ai.localstudio.app.whisper.WhisperFileTranscriber.transcribe]
     * already loads-if-needed and reuses an already-loaded matching seed, so
     * this both primes and *is* the load the first real "Transcribe" tap
     * would otherwise pay for. Best-effort: nothing here is shown to the
     * user, and a failure (no model installed, a transient decode error on
     * the bundled sample) just means the first real file pays the full cost
     * as before — never worth an error dialog.
     *
     * Skipped while [ai.localstudio.app.benchmark.BenchmarkOrchestrator] is
     * actively running: every whisper.cpp native call in this app —
     * regardless of which engine instance it belongs to — goes through the
     * same process-wide [ai.localstudio.whisper.WhisperBridge.nativeOpMutex].
     * A real device report is why this guard exists: a benchmark's own
     * model load took 44 seconds for a size that had loaded in ~1-2s
     * earlier in the same run, strongly suggesting this exact warm-up call
     * was competing for that lock at the same time — this screen's own
     * warm-up is a nice-to-have, a benchmark run in progress is not
     * something it should ever be allowed to stall.
     */
    private fun warmUpSelectedModel() {
        if (container.benchmarkOrchestrator.state.value is BenchmarkUiState.Running) return
        val seed = container.whisperStore.installedSeed(container.settings.whisperModelId) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val sample = WarmupSample.resolve(this@TranscribeActivity)
                container.whisperFileTranscriber.transcribe(Uri.fromFile(sample), seed, language = null) {}
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        UtilityMenu.inflate(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        UtilityMenu.handle(this, item.itemId) || super.onOptionsItemSelected(item)

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
        // whisperFileTranscriber is NOT released here anymore: it now
        // belongs to AppContainer.fileTranscriptionRunner, an application-
        // scoped batch job that must survive this very Activity being
        // destroyed (see FileTranscriptionRunner's own doc comment for why
        // that recreation happens on completely ordinary navigation, not
        // just backgrounding the whole app). It is freed the same way the
        // router's own models are — only by AppContainer.releaseWhisperEngines
        // under real memory pressure, never by this screen closing.
        //
        // whisperMicSession/voskRecognizer are still this screen's own —
        // mic capture cannot usefully continue once it is gone. Off the
        // main thread: release() blocks until any in-flight call actually
        // unwinds (see its own doc comment) — fine on a background
        // coroutine, an ANR risk called straight from onDestroy.
        CoroutineScope(Dispatchers.IO).launch {
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
        container.fileTranscriptionRunner.start(seed)
    }

    private fun stop() {
        container.fileTranscriptionRunner.stop()
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
                showErrorDialog(getString(R.string.transcribe_mic_error, error.describeForUser()))
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
                showErrorDialog(getString(R.string.transcribe_mic_error, e.describeForUser()))
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
                showErrorDialog(getString(R.string.transcribe_mic_error, error.describeForUser()))
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
        container.routerSessionActive = true
        renderRouterState()
        binding.routerTranscriptText.text = ""
        binding.routerTranscriptText.visibility = View.VISIBLE
        routerLines = mutableListOf()
        routerCurrentLineKey = null

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
                showErrorDialog(getString(R.string.transcribe_mic_error, e.describeForUser()))
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
                val key = "${segment.modelId}|${segment.startMs}"
                if (key == routerCurrentLineKey && routerLines.isNotEmpty()) {
                    routerLines[routerLines.size - 1] = line
                } else {
                    routerLines += line
                    routerCurrentLineKey = key
                }
                binding.routerTranscriptText.text = routerLines.joinToString("\n")
            }
            routerActive = false
            container.routerSessionActive = false
            routerSession = null
            renderRouterState()
            // Same convention as startMic()/startVosk(): the segments Flow
            // completing tells nothing about *why* on its own — normal
            // finish()/cancel() and an unexpected failure inside the
            // session both end up here. lastError is what distinguishes
            // them (see DefaultStreamingRoutingSession's own doc comment)
            // — without this check, a router session that died from a bug
            // partway through just went quiet with no error shown at all.
            session.lastError?.let { error ->
                showErrorDialog(getString(R.string.transcribe_mic_error, error.describeForUser()))
            }
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
        container.routerSessionActive = false
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
     * Loads and plays [uri] straight from its own SAF/file source — no
     * decode through [ai.localstudio.app.whisper.MediaCodecAudioSource],
     * deliberately: the point is to hear the *original* clip next to the
     * transcript, not a resampled copy of what whisper.cpp actually
     * received. One shared player bar (see activity_transcribe.xml), not
     * one per result row — only one file plays at a time (one
     * MediaPlayer), so a real seek position belongs to a single control
     * next to the file picker, not duplicated across rows and mixed in
     * with transcription output.
     */
    private fun selectAndPlay(uri: Uri, name: String) {
        if (playingUri == uri) {
            togglePlayerPlayPause()
            return
        }
        stopPlayback()
        playingUri = uri
        binding.playerBar.visibility = View.VISIBLE
        binding.playerFileNameText.text = name
        binding.playerSeekBar.progress = 0
        binding.playerTimeText.text = formatPlayerTime(0, 0)
        val player = MediaPlayer()
        try {
            player.setDataSource(this, uri)
            player.setOnPreparedListener {
                it.start()
                binding.playerSeekBar.max = it.duration.coerceAtLeast(0)
                updatePlayerButton(playing = true)
                startPlayerTicker()
            }
            player.setOnCompletionListener { stopPlayback() }
            player.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            player.release()
            Toast.makeText(this, getString(R.string.transcribe_play_failed, e.describeForUser()), Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
    }

    private fun togglePlayerPlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            updatePlayerButton(playing = false)
        } else {
            player.start()
            updatePlayerButton(playing = true)
            startPlayerTicker()
        }
    }

    private fun updatePlayerButton(playing: Boolean) {
        binding.playerPlayPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        binding.playerPlayPauseButton.contentDescription = getString(if (playing) R.string.transcribe_pause else R.string.transcribe_play)
    }

    /** Polls MediaPlayer.currentPosition rather than relying on a callback — MediaPlayer has none for playback progress. Stops itself once playback is no longer running; startPlayerTicker restarts it whenever play resumes. */
    private fun startPlayerTicker() {
        playerTickerJob?.cancel()
        playerTickerJob = lifecycleScope.launch {
            while (isActive) {
                val player = mediaPlayer
                if (player == null || !player.isPlaying) break
                if (!playerSeekBarDragging) binding.playerSeekBar.progress = player.currentPosition
                binding.playerTimeText.text = formatPlayerTime(player.currentPosition, player.duration)
                delay(300)
            }
        }
    }

    private fun formatPlayerTime(positionMs: Int, durationMs: Int): String {
        fun format(ms: Int): String {
            val totalSeconds = (ms / 1000).coerceAtLeast(0)
            return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
        }
        return "${format(positionMs)} / ${format(durationMs)}"
    }

    private fun stopPlayback() {
        playerTickerJob?.cancel()
        playerTickerJob = null
        mediaPlayer?.let { player -> runCatching { player.stop() }; player.release() }
        mediaPlayer = null
        playingUri = null
        binding.playerBar.visibility = View.GONE
    }

    /** The single reactive rendering point for the batch job — see onCreate's own comment for why this replaced separate render()/setRunning() functions each racing their own idea of "results" and "running". */
    private fun renderResults(results: List<TranscriptionResult>, running: Boolean) {
        binding.transcribeEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        binding.transcribeResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
        binding.transcribeStartButton.isEnabled = results.isNotEmpty() && !running
        binding.transcribeStopButton.isEnabled = running
        binding.transcribeProgress.visibility = if (running) View.VISIBLE else View.GONE
        binding.pickFileButton.isEnabled = !running
        binding.pickFolderButton.isEnabled = !running
        adapter.submit(results)
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {
        private var items: List<TranscriptionResult> = emptyList()

        fun submit(next: List<TranscriptionResult>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemTranscribeResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(val binding: ItemTranscribeResultBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(result: TranscriptionResult) {
                binding.resultFileName.text = result.name
                binding.resultStatus.text = when (result.status) {
                    TranscriptionStatus.PENDING -> getString(R.string.transcribe_status_pending)
                    TranscriptionStatus.RUNNING -> getString(R.string.transcribe_status_running)
                    TranscriptionStatus.DONE -> getString(R.string.transcribe_status_done, result.savedAs ?: "")
                    TranscriptionStatus.ERROR -> getString(R.string.transcribe_status_error, result.error ?: "")
                    TranscriptionStatus.CANCELLED -> result.savedAs?.let { getString(R.string.transcribe_status_cancelled_saved, it) }
                        ?: getString(R.string.transcribe_status_cancelled)
                }
                binding.resultText.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultText.text = result.text
                binding.resultCopyButton.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultCopyButton.setOnClickListener { copyToClipboard(result.text) }
                binding.resultShareButton.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultShareButton.setOnClickListener { shareResult(result) }

                // Tap the row to load it into the shared player bar (see
                // activity_transcribe.xml) — no per-row play control here
                // anymore, that's exactly what mixed playback into the
                // transcription output.
                binding.root.setOnClickListener { selectAndPlay(result.uri, result.name) }
            }
        }
    }

    /**
     * The saved .txt lives under this app's own private filesDir — invisible
     * to any file manager, Downloads app, or other app without root.
     * Sharing plain text (EXTRA_TEXT) handed over the transcript's
     * *content*, not a file — a real ask, reported directly: text pasted
     * into a share target isn't the same as a .txt the user can actually
     * save. Once [TranscriptionResult.savedAs] names the file on disk, this
     * shares that file itself via [FileProvider] (see
     * transcript_file_paths.xml and the AndroidManifest provider entry —
     * file:// URIs are blocked by StrictMode for cross-app sharing on
     * modern Android, hence content:// through this). Falls back to plain
     * text only for a row that has visible text but hasn't been saved yet
     * (RUNNING, or ERROR after some text arrived) — FileTranscriptionRunner
     * only ever saves on DONE or a non-blank CANCELLED.
     */
    private fun shareResult(result: TranscriptionResult) {
        val savedName = result.savedAs
        val file = savedName?.let { File(File(filesDir, "transcripts"), it) }
        if (file != null && file.isFile) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.transcribe_share)))
        } else {
            shareText(result.text)
        }
    }

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
