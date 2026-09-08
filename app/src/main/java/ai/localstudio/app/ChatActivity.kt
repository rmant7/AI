package ai.localstudio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
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
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.pipeline.ConversationTurn
import ai.localstudio.core.pipeline.NodeValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
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
    private var generationJob: kotlinx.coroutines.Job? = null
    // Distinguishes "user tapped stop" from every other way generate() can
    // fail, so cancelling shows a plain "stopped" line instead of an error.
    private var stoppedByUser = false
    private var previewJob: kotlinx.coroutines.Job? = null
    // Whatever was already typed before the mic was tapped — live preview
    // updates the field repeatedly while recording, and each update must
    // still be "the old text plus what's been said so far", not overwrite it.
    private var recordingPrefix = ""

    // Staged for exactly one turn, then cleared — an attached image is a
    // question about *this* photo, not something to keep resending on every
    // later message the way a document's extracted text is kept in memory.
    private var pendingImage: ImageRef? = null

    private val pickDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val mimeType = contentResolver.getType(it).orEmpty()
            if (mimeType.startsWith("image/")) attachImage(it) else ingestDocument(it)
        }
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

        binding.sendButton.setOnClickListener { if (isGenerating) stopGeneration() else send() }
        binding.attachButton.setOnClickListener { pickDocument.launch("*/*") }
        binding.pendingImageClear.setOnClickListener { clearPendingImage() }
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
        // Cleared immediately, same as the text field above: this turn owns
        // whatever was staged, and a lingering thumbnail after sending would
        // read as "still attached" for the next message too.
        val attachment: NodeValue = pendingImage?.let { NodeValue.Image(it) } ?: NodeValue.Empty
        clearPendingImage()

        // Read right before the request, not after: this is what makes
        // "какие провайдеры были разрешены" answerable for this exact turn
        // rather than for whatever the orchestrator happened to build last.
        container.appLog.record(
            "SEND",
            "enabledProviders=${container.settings.enabledProviderIds} route=${container.runtimeLabel}",
        )

        if (container.settings.compareMode) {
            val sources = container.compareCandidates()
            if (sources.size >= 2) {
                container.appLog.record("SEND_COMPARE", "sources=${sources.map { it.first }}")
                sendCompare(text, history, attachedDocuments, attachment, sources)
                return
            }
        }

        generationJob = lifecycleScope.launch {
            // A placeholder bubble appears immediately, then grows in place
            // as chunks arrive — every runtime already streams token by
            // token underneath (see NodeExecutors.textGeneration), but until
            // now nothing surfaced that here: the whole answer only ever
            // appeared at once, at the very end, so a slow model looked
            // indistinguishable from a hung one for however long it ran.
            val startedAt = System.currentTimeMillis()
            adapter.add(Message.assistant(body = "…", details = null).copy(timestamp = startedAt))
            val placeholderIndex = adapter.lastIndex()
            binding.messages.scrollToPosition(placeholderIndex)

            val partial = MutableStateFlow<String?>(null)
            // conflate(): a fast model can emit far more chunks per second
            // than the UI thread can usefully render — this drops
            // intermediate values under load instead of queueing them, so
            // rendering never falls behind no matter how long generation runs.
            val renderJob = launch {
                partial.filterNotNull().conflate().collect { text ->
                    adapter.update(placeholderIndex, Message.assistant(body = text, details = null).copy(timestamp = startedAt))
                }
            }

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
                                attachment = attachment,
                                memoryEnabled = container.settings.memoryEnabled,
                                history = history,
                                attachedDocuments = attachedDocuments,
                            ),
                            onPartialText = { partial.value = it },
                        )
                    }
                }
            }
            renderJob.cancel()
            isGenerating = false
            generationJob = null
            setBusy(false)
            if (stoppedByUser) {
                stoppedByUser = false
                adapter.update(placeholderIndex, Message.error(body = getString(R.string.chat_stopped), details = null))
                binding.messages.scrollToPosition(adapter.itemCount - 1)
                persist()
                return@launch
            }
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
                    adapter.update(
                        placeholderIndex,
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
                    adapter.update(placeholderIndex, Message.error(body = body, details = error.javaClass.simpleName))
                }
            binding.messages.scrollToPosition(adapter.itemCount - 1)
            persist()
        }
    }

    /**
     * Settings.compareMode's parallel path: every enabled source gets its
     * own single-candidate Orchestrator (see AppContainer.compareCandidates)
     * and runs independently instead of being tried as a fallback chain —
     * one answer completing does not stop or skip the others. Each source
     * gets its own bubble the moment it finishes, not one shared bubble all
     * of them fill in — that made every source's text copy together as a
     * single blob, with no way to grab just one answer.
     */
    private fun sendCompare(
        text: String,
        history: List<ConversationTurn>,
        attachedDocuments: List<String>,
        attachment: NodeValue,
        sources: List<Pair<String, ai.localstudio.core.engine.Orchestrator>>,
    ) {
        generationJob = lifecycleScope.launch {
            try {
                // A placeholder per source, all added up front on Main before
                // any async work starts (so no two sources ever race to
                // mutate the adapter) — then each grows in place as its own
                // chunks arrive, same reasoning as the single-source send():
                // compare mode already means waiting on the slowest of
                // several candidates, so an answer streaming in as it's
                // produced matters even more here than in the ordinary path.
                val startedAt = System.currentTimeMillis()
                val placeholderIndexes = sources.map { (label, _) ->
                    adapter.add(Message.assistant(body = "**$label:**\n…", details = null).copy(timestamp = startedAt))
                    adapter.lastIndex()
                }
                binding.messages.scrollToPosition(adapter.itemCount - 1)

                val jobs = sources.mapIndexed { index, (label, orchestrator) ->
                    val placeholderIndex = placeholderIndexes[index]
                    async(Dispatchers.IO) {
                        val partial = MutableStateFlow<String?>(null)
                        val renderJob = launch(Dispatchers.Main) {
                            partial.filterNotNull().conflate().collect { partialText ->
                                adapter.update(
                                    placeholderIndex,
                                    Message.assistant(body = "**$label:**\n$partialText", details = null).copy(timestamp = startedAt),
                                )
                            }
                        }
                        // Deliberately not a blanket runCatching: a per-source
                        // timeout must render as this source's own error while
                        // the others keep going, but a real cancellation (the
                        // Stop button, or this whole turn's coroutine being
                        // torn down) must propagate and actually stop this
                        // source instead of being swallowed and rendered as
                        // just another error — the exact bug just fixed in
                        // FallbackTextRuntime for the sequential fallback path.
                        val rendered = try {
                            val answer = kotlinx.coroutines.withTimeout(GENERATION_TIMEOUT_MS) {
                                orchestrator.handle(
                                    UserRequest(
                                        conversationId = conversationId,
                                        text = text,
                                        attachment = attachment,
                                        memoryEnabled = container.settings.memoryEnabled,
                                        history = history,
                                        attachedDocuments = attachedDocuments,
                                    ),
                                    onPartialText = { partial.value = it },
                                )
                            }
                            answer.text.ifBlank { "(пустой ответ)" }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            container.appLog.record("GENERATION_ERROR", "$label: timeout after ${GENERATION_TIMEOUT_MS}ms")
                            "Ошибка: не ответила за ${GENERATION_TIMEOUT_MS / 1000} с"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            container.appLog.record("GENERATION_ERROR", "$label: ${e.javaClass.simpleName}: ${e.message}")
                            "Ошибка: ${e.message ?: e.toString()}"
                        } finally {
                            // In a finally, not just after the try: the
                            // CancellationException branch above rethrows
                            // rather than falling through to the line after
                            // this block, and that render collector must
                            // still stop either way.
                            renderJob.cancel()
                        }
                        withContext(Dispatchers.Main) {
                            adapter.update(placeholderIndex, Message.assistant(body = "**$label:**\n$rendered", details = null).copy(timestamp = startedAt))
                            binding.messages.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                }
                jobs.awaitAll()
            } finally {
                isGenerating = false
                generationJob = null
                setBusy(false)
                stoppedByUser = false
                persist()
            }
        }
        isGenerating = true
        setBusy(true)
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

    /**
     * A GGUF/LiteRT-LM model never sees this — every local runtime ignores
     * [ai.localstudio.core.runtime.GenerationRequest.images] entirely — so
     * this is gated on at least one *enabled* provider actually accepting
     * vision content, checked before doing any work rather than staging an
     * image the eventual answer will silently ignore.
     */
    private fun attachImage(uri: Uri) {
        val visionAvailable = container.settings.enabledProviderIds.any { CloudProviders.byId(it).visionCapable }
        if (!visionAvailable) {
            Toast.makeText(this, R.string.chat_attach_image_no_vision, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val name = DocumentIngest.fileName(this@ChatActivity, uri).ifBlank { "изображение" }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Не удалось открыть файл")
                    if (bytes.size > MAX_IMAGE_BYTES) {
                        throw java.io.IOException(
                            getString(R.string.chat_attach_image_too_large, "%.1f МБ".format(Locale.US, bytes.size / 1_000_000.0)),
                        )
                    }
                    // A content:// URI means nothing outside this process —
                    // core/openai are plain JVM with no Context to resolve
                    // it, so the image is embedded as a self-contained data
                    // URI right here rather than threading Android-specific
                    // access down through the runtime layer.
                    val mimeType = contentResolver.getType(uri).takeIf { !it.isNullOrBlank() } ?: "image/jpeg"
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    ImageRef(uri = "data:$mimeType;base64,$base64")
                }
            }
            result
                .onSuccess { ref ->
                    pendingImage = ref
                    binding.pendingImageLabel.text = getString(R.string.chat_attach_image_pending, name)
                    binding.pendingImageRow.visibility = android.view.View.VISIBLE
                }
                .onFailure { error ->
                    container.appLog.record("ATTACH_ERROR", "${error.javaClass.simpleName}: ${error.message ?: error}")
                    Toast.makeText(
                        this@ChatActivity,
                        getString(R.string.chat_attach_image_read_failed, error.message ?: error.toString()),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun clearPendingImage() {
        pendingImage = null
        binding.pendingImageRow.visibility = android.view.View.GONE
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

    /**
     * Cancelling the coroutine driving generate() is not a no-op gesture:
     * FallbackTextRuntime/LlamaCppRuntime's callbackFlow already tears down
     * on cancellation exactly the way the generation timeout does (native
     * decode stops at the next chunk boundary, nativeCancel() is called from
     * awaitClose) — so this reuses a path that was already made safe rather
     * than adding a second cancellation mechanism. That is what actually lets
     * the user load a different model or switch to cloud right away instead
     * of waiting out a hang.
     */
    private fun stopGeneration() {
        if (!isGenerating) return
        stoppedByUser = true
        generationJob?.cancel()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        // The send button doubles as stop while a turn is in flight, rather
        // than disabling — waiting out a stuck model used to be the only
        // option, which is exactly what made switching to a lighter model or
        // to cloud impossible without force-closing the app.
        binding.sendButton.isEnabled = true
        binding.sendButton.icon = androidx.core.content.ContextCompat.getDrawable(
            this, if (busy) R.drawable.ic_stop else R.drawable.ic_send,
        )
        binding.sendButton.contentDescription = getString(if (busy) R.string.chat_stop else R.string.send)
    }

    private companion object {
        const val MENU_MEMORY = 1
        const val MENU_MODELS = 2
        const val MENU_FILES = 3
        const val MENU_SETTINGS = 4
        const val MENU_HISTORY = 5
        const val MENU_SHARE_CHAT = 6
        const val MENU_CLEAR = 7

        // Was temporarily raised to 30 minutes to measure real on-device
        // timing for heavier local models before picking a production value
        // (see git history). That data is in: the smallest local model
        // answers in under a minute, a ~5.5GB model gets a first token in
        // under two minutes, and anything that doesn't fit in RAM fails on
        // load — long before generation would even start — rather than
        // hanging inside this timeout. 5 minutes gives real answers room
        // without leaving the send button disabled for half an hour on an
        // actual hang.
        const val GENERATION_TIMEOUT_MS = 5 * 60 * 1_000L

        // Turns, not tokens: the context engine's own budget trims whatever
        // does not fit. This just bounds how much history gets rendered and
        // handed over in the first place.
        const val MAX_HISTORY_TURNS = 12

        // Base64 inflates this by ~1.33x on top; most vision-capable APIs
        // reject requests well below what a modern phone camera produces
        // uncompressed, so this exists to fail fast with a clear message
        // instead of sending a multi-minute upload that the server rejects
        // anyway.
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

        // Short enough to read as "live", long enough that Tiny is done
        // transcribing everything so far well before the next tick.
        // Matches UtteranceConfig.refreshMs (docs/12-audio.md) — a validated
        // number from a working implementation, not a guess.
        const val PREVIEW_INTERVAL_MS = 2_000L
        const val PREVIEW_FIRST_DELAY_MS = 700L
    }
}
