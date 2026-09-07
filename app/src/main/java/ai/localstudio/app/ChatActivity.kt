package ai.localstudio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.localstudio.app.attach.DocumentIngest
import ai.localstudio.app.databinding.ActivityChatBinding
import ai.localstudio.app.history.ChatHistoryStore
import ai.localstudio.app.history.Conversation
import ai.localstudio.app.history.toMessage
import ai.localstudio.app.history.toStored
import ai.localstudio.app.whisper.AudioRecorder
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.core.engine.UserRequest
import ai.localstudio.core.pipeline.ConversationTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var container: AppContainer
    private lateinit var history: ChatHistoryStore
    private val adapter = MessageAdapter()
    private var conversationId = "chat-" + System.currentTimeMillis()
    private val recorder = AudioRecorder()
    private var isGenerating = false
    private var previewJob: kotlinx.coroutines.Job? = null
    // Whatever was already typed before the mic was tapped — live preview
    // updates the field repeatedly while recording, and each update must
    // still be "the old text plus what's been said so far", not overwrite it.
    private var recordingPrefix = ""

    private val pickDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { ingestDocument(it) }
    }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleRecording() else Toast.makeText(this, R.string.chat_mic_permission, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        container = AppContainer.get(this)
        history = ChatHistoryStore(this)
        binding.root.applySystemBarInsets(applyImeInset = true)

        binding.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        binding.sendButton.setOnClickListener { send() }
        binding.attachButton.setOnClickListener { pickDocument.launch("*/*") }
        binding.micButton.setOnClickListener { onMicClicked() }

        // The app being killed in the background is routine on Android, not
        // exceptional — resuming the most recent conversation instead of a
        // blank screen is what makes that invisible to the user.
        history.list().firstOrNull()?.let { latest ->
            conversationId = latest.id
            latest.messages.forEach { adapter.add(it.toMessage()) }

            // A conversation whose last saved message is the user's own,
            // with nothing after it, means that turn's generation never
            // finished being persisted — most likely the process died
            // mid-turn (a native crash in llama.cpp cannot be caught the way
            // a Kotlin exception can, so no error bubble was ever shown), or
            // the app was reclaimed by Android while a reply was still being
            // generated. Either way, silence here used to read as "my
            // message vanished" — this turns it into a visible, explained
            // one instead, and only fires once: persisting it below means
            // the next launch sees a non-"Вы" last message and stays quiet.
            val lastMessage = adapter.messages().lastOrNull()
            if (lastMessage != null && lastMessage.role == "Вы") {
                container.appLog.record(
                    "INTERRUPTED_TURN",
                    "Conversation $conversationId: last message has no reply after relaunch",
                )
                adapter.add(Message.error(body = getString(R.string.chat_interrupted), details = null))
                persist()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        container.whisperEngine.release()
        container.whisperPreviewEngine.release()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MEMORY, 0, memoryTitle()).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MODELS, 1, R.string.menu_models)
        menu.add(0, MENU_FILES, 2, R.string.menu_files)
        menu.add(0, MENU_SETTINGS, 3, R.string.menu_settings)
        menu.add(0, MENU_HISTORY, 4, R.string.menu_history)
        menu.add(0, MENU_SHARE_CHAT, 5, R.string.menu_share_chat)
        menu.add(0, MENU_CLEAR, 6, R.string.menu_clear)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_MEMORY)?.setTitle(memoryTitle())
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_SETTINGS -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        MENU_MODELS -> {
            startActivity(Intent(this, ModelsActivity::class.java))
            true
        }

        MENU_FILES -> {
            startActivity(Intent(this, FilesActivity::class.java))
            true
        }

        MENU_MEMORY -> {
            container.settings.memoryEnabled = !container.settings.memoryEnabled
            invalidateOptionsMenu()
            updateStatus()
            true
        }

        MENU_CLEAR -> {
            adapter.clear()
            conversationId = "chat-" + System.currentTimeMillis()
            true
        }

        MENU_HISTORY -> {
            showHistory()
            true
        }

        MENU_SHARE_CHAT -> {
            shareChat()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** Shares the whole visible conversation as plain text — one message per paragraph, in order. */
    private fun shareChat() {
        val messages = adapter.messages()
        if (messages.isEmpty()) {
            Toast.makeText(this, R.string.chat_share_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val transcript = messages.joinToString("\n\n") { message ->
            val time = Message.formatTime(message.timestamp)
            val header = if (time.isBlank()) message.role else "${message.role} ($time)"
            "$header:\n${message.body}"
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.menu_share_chat)))
    }

    private fun showHistory() {
        val conversations = history.list()
        if (conversations.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.history_empty, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val labels = conversations.map { "${it.title}\n${format.format(Date(it.updatedAt))}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_history)
            .setItems(labels) { _, index -> openConversation(conversations[index]) }
            .setNegativeButton(R.string.history_delete) { _, _ -> pickAndDelete(conversations) }
            .show()
    }

    private fun pickAndDelete(conversations: List<Conversation>) {
        val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val labels = conversations.map { "${it.title}\n${format.format(Date(it.updatedAt))}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.history_delete)
            .setItems(labels) { _, index ->
                val target = conversations[index]
                history.delete(target.id)
                if (target.id == conversationId) {
                    adapter.clear()
                    conversationId = "chat-" + System.currentTimeMillis()
                }
            }
            .show()
    }

    private fun openConversation(conversation: Conversation) {
        conversationId = conversation.id
        adapter.clear()
        conversation.messages.forEach { adapter.add(it.toMessage()) }
        binding.messages.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
    }

    private fun persist() {
        val messages = adapter.messages()
        if (messages.isEmpty()) return
        val stored = messages.map { it.toStored() }
        history.save(
            Conversation(
                id = conversationId,
                title = ChatHistoryStore.titleFor(stored),
                updatedAt = System.currentTimeMillis(),
                messages = stored,
            ),
        )
    }

    private fun memoryTitle(): Int =
        if (container.settings.memoryEnabled) R.string.memory_on else R.string.memory_off

    private fun updateStatus() {
        val memory = if (container.settings.memoryEnabled) "память вкл" else "память выкл"
        // container.activeModelName, not settings.chatModel: that getter is
        // scoped to whichever provider is selected in the Settings dropdown
        // right now, independent of which providers are actually enabled —
        // showing a Gemini model name while only local was enabled was that
        // mismatch, not a sign the router itself was using Gemini.
        binding.statusText.text = "${container.activeModelName} · ${container.runtimeLabel} · $memory"
    }

    private fun send() {
        // Belt and suspenders on top of the disabled send button: a native
        // llama.cpp context is not safe to decode into from two calls at
        // once, so a second request slipping through while the first is
        // still in flight risks hanging the session rather than erroring —
        // exactly what "sent three messages, got zero replies, no error
        // either" looks like from the outside.
        if (isGenerating) return
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        binding.input.setText("")
        adapter.add(Message.user(text))
        binding.messages.scrollToPosition(adapter.itemCount - 1)
        persist()
        isGenerating = true
        setBusy(true)

        // Everything already on screen except the turn just added above,
        // which the orchestrator already gets as `text` — without this the
        // model answers each message as if it were the start of a brand new
        // conversation, since memory recall only fires on an explicit
        // "remind me" style message, not on ordinary follow-ups.
        val history = adapter.messages()
            .dropLast(1)
            .filterNot { it.isError }
            .takeLast(MAX_HISTORY_TURNS)
            .map { ConversationTurn(it.role, it.body) }
        val attachedDocuments = container.documents.list().map { it.name }

        // Read right before the request, not after: this is what makes
        // "какие провайдеры были разрешены" answerable for this exact turn
        // rather than for whatever the orchestrator happened to build last.
        container.appLog.record(
            "SEND",
            "enabledProviders=${container.settings.enabledProviderIds} route=${container.runtimeLabel}",
        )

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // A hang anywhere below this — native, network, wherever
                    // — must not be silent forever. Cancelling here at least
                    // frees the UI to try again instead of the send button
                    // staying disabled with nothing to explain why.
                    kotlinx.coroutines.withTimeout(GENERATION_TIMEOUT_MS) {
                        container.orchestrator().handle(
                            UserRequest(
                                conversationId = conversationId,
                                text = text,
                                memoryEnabled = container.settings.memoryEnabled,
                                history = history,
                                attachedDocuments = attachedDocuments,
                            ),
                        )
                    }
                }
            }
            isGenerating = false
            setBusy(false)
            result
                .onSuccess { answer ->
                    // FallbackTextRuntime already embeds "Ответ от: <label>"
                    // whenever 2+ candidates are configured (any cloud
                    // provider, via its own model rotation, counts as 2+).
                    // The one case that bypasses it — exactly one candidate,
                    // i.e. pure local-only — gets the same line added here
                    // instead, so an answer is never shown with no
                    // indication at all of which model actually produced it.
                    val body = answer.text.ifBlank { "(пустой ответ)" }.let { answerText ->
                        container.soleAnswererLabel?.let { label -> "$answerText\n\n---\nОтвет от: $label" } ?: answerText
                    }
                    adapter.add(
                        Message.assistant(
                            body = body,
                            details = buildString {
                                append(answer.plan.capabilities.joinToString(", ") { it.id })
                                answer.context?.let { append(" · контекст: ${it.fragments.size} фрагм.") }
                                if (answer.droppedFragments > 0) append(", отброшено ${answer.droppedFragments}")
                                append(" · ${answer.trace.sumOf { it.durationMs }} мс")
                            },
                        ),
                    )
                }
                .onFailure { error ->
                    val body = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                        "Модель не ответила за ${GENERATION_TIMEOUT_MS / 1000} с. Возможно, модель слишком тяжёлая " +
                            "для этого устройства, или что-то зависло — попробуйте ещё раз или выберите модель полегче."
                    } else {
                        error.message ?: error.toString()
                    }
                    container.appLog.record("GENERATION_ERROR", "${error.javaClass.simpleName}: $body")
                    adapter.add(Message.error(body = body, details = error.javaClass.simpleName))
                }
            binding.messages.scrollToPosition(adapter.itemCount - 1)
            persist()
        }
    }

    private fun ingestDocument(uri: Uri) {
        lifecycleScope.launch {
            val name = DocumentIngest.fileName(this@ChatActivity, uri).ifBlank { "файл" }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = DocumentIngest.extractText(this@ChatActivity, uri)
                    DocumentIngest.chunk(text)
                }
            }
            result
                .onSuccess { chunks ->
                    container.rememberDocument(name, chunks)
                    container.settings.memoryEnabled = true
                    invalidateOptionsMenu()
                    updateStatus()
                    Toast.makeText(this@ChatActivity, getString(R.string.chat_attach_added, name, chunks.size), Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    container.appLog.record("ATTACH_ERROR", "${error.javaClass.simpleName}: ${error.message ?: error}")
                    Toast.makeText(
                        this@ChatActivity,
                        getString(R.string.chat_attach_failed, error.message ?: error.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun onMicClicked() {
        if (recorder.isRecording) {
            toggleRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            toggleRecording()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun toggleRecording() {
        if (!recorder.isRecording) {
            val seed = container.whisperStore.installedSeed(container.settings.whisperModelId)
            if (seed == null) {
                Toast.makeText(this, R.string.chat_mic_no_model, Toast.LENGTH_LONG).show()
                return
            }
            recordingPrefix = binding.input.text?.toString().orEmpty()
            recorder.start()
            binding.micButton.setIconResource(R.drawable.ic_stop)
            binding.statusText.text = getString(R.string.chat_recording)
            startPreviewLoop()
            return
        }

        finalizeRecording()
    }

    /**
     * Stops recording and runs the one accurate transcription pass — called
     * either from a manual tap on the mic button, or automatically once
     * [AudioRecorder.shouldFinalize] reports the documented pause (0.8s of
     * silence after speech) or the 25s window filling up, so a turn does not
     * require tapping stop at all if the pause is left to do it.
     */
    private fun finalizeRecording() {
        previewJob?.cancel()
        previewJob = null
        val audio = recorder.stop()
        binding.micButton.setIconResource(R.drawable.ic_mic)
        updateStatus()
        val seed = container.whisperStore.installedSeed(container.settings.whisperModelId) ?: return

        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.chat_transcribing)
            val result = withContext(Dispatchers.Default) {
                runCatching { container.whisperEngine.transcribe(seed, audio) }
            }
            updateStatus()
            result
                .onSuccess { text -> if (text.isNotBlank()) setInputText(text) }
                .onFailure { error ->
                    Toast.makeText(this@ChatActivity, error.message ?: error.toString(), Toast.LENGTH_LONG).show()
                }
        }
    }

    /**
     * Re-transcribes the recording so far with the small, fast Tiny model
     * every couple of seconds — never the model actually selected for the
     * final pass, which may be far larger and take many seconds per call on
     * its own. Running that repeatedly during recording would queue up
     * overlapping multi-second calls instead of ever feeling live; Tiny is
     * fast enough that "re-run the whole thing so far" reads as continuous.
     *
     * Also watches for [AudioRecorder.shouldFinalize] — the documented
     * pause-based end of an utterance — and finalizes automatically rather
     * than only ever reacting to a manual tap on stop.
     */
    private fun startPreviewLoop() {
        val previewSeed = WhisperModels.byId(WhisperModels.TINY_ID) ?: return
        val previewAvailable = container.whisperStore.isInstalled(previewSeed)

        previewJob = lifecycleScope.launch {
            // Shorter than the steady-state interval: a short utterance can
            // otherwise end (stop tapped) before the first cycle ever
            // completes, which is indistinguishable from the preview not
            // working at all.
            kotlinx.coroutines.delay(PREVIEW_FIRST_DELAY_MS)
            while (recorder.isRecording) {
                if (recorder.shouldFinalize) {
                    finalizeRecording()
                    return@launch
                }
                if (previewAvailable) {
                    val snapshot = recorder.snapshot()
                    if (snapshot.isNotEmpty()) {
                        val partial = runCatching { container.whisperPreviewEngine.transcribe(previewSeed, snapshot) }
                            .getOrNull()
                        if (!partial.isNullOrBlank()) setInputText(partial)
                    }
                }
                if (!recorder.isRecording) break
                kotlinx.coroutines.delay(PREVIEW_INTERVAL_MS)
            }
        }
    }

    private fun setInputText(recognized: String) {
        val text = if (recordingPrefix.isBlank()) recognized else "$recordingPrefix $recognized"
        binding.input.setText(text)
        binding.input.setSelection(text.length)
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendButton.isEnabled = !busy
    }

    private companion object {
        const val MENU_MEMORY = 1
        const val MENU_MODELS = 2
        const val MENU_FILES = 3
        const val MENU_SETTINGS = 4
        const val MENU_HISTORY = 5
        const val MENU_SHARE_CHAT = 6
        const val MENU_CLEAR = 7

        // Generous on purpose: a large local model on a slow phone can
        // legitimately take a while to produce a first token. This exists to
        // catch the case where nothing is ever coming back, not to rush a
        // model that is working.
        const val GENERATION_TIMEOUT_MS = 180_000L

        // Turns, not tokens: the context engine's own budget trims whatever
        // does not fit. This just bounds how much history gets rendered and
        // handed over in the first place.
        const val MAX_HISTORY_TURNS = 12

        // Short enough to read as "live", long enough that Tiny is done
        // transcribing everything so far well before the next tick.
        // Matches UtteranceConfig.refreshMs (docs/12-audio.md) — a validated
        // number from a working implementation, not a guess.
        const val PREVIEW_INTERVAL_MS = 2_000L
        const val PREVIEW_FIRST_DELAY_MS = 700L
    }
}
