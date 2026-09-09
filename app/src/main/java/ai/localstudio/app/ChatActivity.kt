package ai.localstudio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.exifinterface.media.ExifInterface
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
import ai.localstudio.core.runtime.ANSWERED_BY_LABEL
import ai.localstudio.core.model.ImageRef
import ai.localstudio.core.pipeline.ConversationTurn
import ai.localstudio.core.pipeline.NodeValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Documents attached during *this* open chat, not [AppContainer]'s whole
     * shared library (container.documents.list(), which is genuinely global
     * across every chat — see FilesActivity's own doc comment). That
     * distinction used to not matter: attachedDocuments only ever produced a
     * one-line "user attached these files" mention. It started mattering the
     * moment a document's actual extracted content began being force-fetched
     * every turn (see NodeExecutors.contextBuild) — the global list would
     * have force-fed an unrelated PDF from a *different* chat, attached
     * hours or days earlier, into every unrelated turn of this one, and did
     * exactly that on a real device before this existed: asked about an
     * attached photo, the local model brought up "no information in the
     * provided PDF" — a file this conversation never even attached.
     */
    private val sessionDocumentNames = mutableListOf<String>()

    private val pickDocument = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val mimeType = contentResolver.getType(it).orEmpty()
            if (mimeType.startsWith("image/")) attachImage(it) else ingestDocument(it)
        }
    }

    /**
     * The history screen only reports what happened; loading and clearing
     * stay here, where the conversation actually lives.
     */
    private val openHistory = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        data.getStringExtra(HistoryActivity.EXTRA_CONVERSATION_ID)?.let { id ->
            history.load(id)?.let { openConversation(it) }
        }
        // A chat can be deleted while it is the one on screen. Left alone,
        // the next message would recreate the file that was just deleted.
        data.getStringExtra(HistoryActivity.EXTRA_DELETED_ID)?.let { deleted ->
            if (deleted == conversationId) startNewConversation()
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
        // RecyclerView's default ItemAnimator cross-fades a changed item —
        // old view faded out, new view faded in — on every notifyItemChanged.
        // For a bubble growing in place several times a second while
        // streaming, that fade reads as the whole answer flickering/jittering
        // instead of text smoothly appearing, which is the one thing this
        // update is actually supposed to look like.
        (binding.messages.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

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
            if (lastMessage != null && lastMessage.role == Message.ROLE_USER) {
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
            startNewConversation()
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
        openHistory.launch(HistoryActivity.intent(this))
    }

    /**
     * Fire-and-forget, and only when leaving a conversation rather than
     * after every turn: MEMORY_UPDATE already writes each turn's exchange
     * as WORKING memory (see NodeExecutors) whenever memory is on, and
     * nothing ever turned that into anything durable or cleared it —
     * consolidate() existed but nothing called it, so working memory just
     * grew forever, unbounded, for the life of the process. Now that memory
     * persists across restarts too (FileMemoryStore), leaving that
     * unconsolidated would mean it grows forever on disk instead — this is
     * what actually distills a finished conversation into durable memories
     * and clears its working set, using whichever model this app would
     * otherwise answer with (see AppContainer's own comment on why).
     */
    private fun consolidatePreviousConversation(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { container.memory.consolidate(id) }
        }
    }

    private fun startNewConversation() {
        consolidatePreviousConversation(conversationId)
        adapter.clear()
        conversationId = "chat-" + System.currentTimeMillis()
        sessionDocumentNames.clear()
        updateStatus()
    }

    private fun openConversation(conversation: Conversation) {
        consolidatePreviousConversation(conversationId)
        conversationId = conversation.id
        adapter.clear()
        conversation.messages.forEach { adapter.add(it.toMessage()) }
        binding.messages.scrollToPosition((adapter.itemCount - 1).coerceAtLeast(0))
        // Which documents were attached during this conversation isn't
        // persisted (see sessionDocumentNames' own comment) — reopening it
        // starts with none "active", falling back to ordinary lexical
        // memory search for anything attached here previously rather than
        // force-including it again.
        sessionDocumentNames.clear()
        updateStatus()
    }

    private fun persist() {
        val messages = adapter.messages()
        if (messages.isEmpty()) return
        val stored = messages.map { it.toStored() }
        history.save(
            Conversation(
                id = conversationId,
                title = ChatHistoryStore.titleFor(stored, getString(R.string.history_untitled_chat)),
                updatedAt = System.currentTimeMillis(),
                messages = stored,
                // Carried over rather than dropped: this runs after every
                // message, so building the record from scratch would quietly
                // undo a rename on the very next thing the user said.
                customTitle = history.load(conversationId)?.customTitle,
            ),
        )
    }

    private fun memoryTitle(): Int =
        if (container.settings.memoryEnabled) R.string.memory_on else R.string.memory_off

    private fun updateStatus() {
        val memory = getString(if (container.settings.memoryEnabled) R.string.status_memory_on else R.string.status_memory_off)
        // container.activeModelName, not settings.chatModel: that getter is
        // scoped to whichever provider is selected in the Settings dropdown
        // right now, independent of which providers are actually enabled —
        // showing a Gemini model name while only local was enabled was that
        // mismatch, not a sign the router itself was using Gemini.
        val files = if (sessionDocumentNames.isEmpty()) {
            ""
        } else {
            " · " + resources.getQuantityString(
                R.plurals.chat_status_files, sessionDocumentNames.size, sessionDocumentNames.size,
            )
        }
        binding.statusText.text = "${container.activeModelName} · ${container.runtimeLabel} · $memory$files"
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
        // An attached image or document is itself the message for anyone
        // who just wants "look at this" answered — every other chat app
        // sends a picture with no caption the same way. Requiring typed
        // text on top of that turned attaching something into two steps
        // where one should do, and the second one added nothing the
        // attachment didn't already say.
        val hasAttachment = pendingImage != null || sessionDocumentNames.isNotEmpty()
        if (text.isEmpty() && !hasAttachment) return

        binding.input.setText("")
        adapter.add(Message.user(text, imageDataUri = pendingImage?.uri))
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
        val attachedDocuments = sessionDocumentNames.toList()
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
            // Sampled on a timer, not one render per chunk: a model can
            // produce several tokens per hundred milliseconds, and each
            // render re-parses the whole accumulated answer as markdown
            // (MessageAdapter.onBindViewHolder) — doing that on every single
            // chunk competes on the same main-thread queue as the Stop
            // button's own click, which is exactly the kind of contention
            // that can make Stop feel unresponsive during a fast stream.
            // Sampling bounds that load to a fixed rate regardless of how
            // fast the model actually streams.
            val renderJob = launch {
                var lastRendered: String? = null
                while (isActive) {
                    val text = partial.value
                    if (text != null && text != lastRendered) {
                        adapter.update(placeholderIndex, Message.assistant(body = text, details = null).copy(timestamp = startedAt))
                        lastRendered = text
                    }
                    delay(RENDER_INTERVAL_MS)
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
                    val body = answer.text.ifBlank { getString(R.string.chat_empty_answer) }.let { answerText ->
                        container.soleAnswererLabel?.let { label -> "$answerText\n\n---\n$ANSWERED_BY_LABEL$label" } ?: answerText
                    }
                    adapter.update(
                        placeholderIndex,
                        Message.assistant(
                            body = body,
                            details = buildString {
                                append(answer.plan.capabilities.joinToString(", ") { it.id })
                                answer.context?.let { append(" · ").append(getString(R.string.chat_detail_context_fragments, it.fragments.size)) }
                                if (answer.droppedFragments > 0) append(getString(R.string.chat_detail_dropped, answer.droppedFragments))
                                append(" · ").append(getString(R.string.chat_detail_duration_ms, answer.trace.sumOf { it.durationMs }))
                            },
                        ),
                    )
                }
                .onFailure { error ->
                    val body = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                        getString(R.string.chat_timeout_message, GENERATION_TIMEOUT_MS / 1000)
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
                        // Sampled on a timer, same reasoning as send()'s
                        // renderJob: a per-chunk markdown re-render on the
                        // main thread competes with the Stop button's click
                        // for the same queue, and compare mode runs several
                        // of these at once.
                        val renderJob = launch(Dispatchers.Main) {
                            var lastRendered: String? = null
                            while (isActive) {
                                val text = partial.value
                                if (text != null && text != lastRendered) {
                                    adapter.update(
                                        placeholderIndex,
                                        Message.assistant(body = "**$label:**\n$text", details = null).copy(timestamp = startedAt),
                                    )
                                    lastRendered = text
                                }
                                delay(RENDER_INTERVAL_MS)
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
                            answer.text.ifBlank { getString(R.string.chat_empty_answer) }
                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                            container.appLog.record("GENERATION_ERROR", "$label: timeout after ${GENERATION_TIMEOUT_MS}ms")
                            getString(R.string.chat_compare_timeout_error, GENERATION_TIMEOUT_MS / 1000)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            container.appLog.record("GENERATION_ERROR", "$label: ${e.javaClass.simpleName}: ${e.message}")
                            getString(R.string.chat_compare_generic_error, e.message ?: e.toString())
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
            val name = DocumentIngest.fileName(this@ChatActivity, uri).ifBlank { getString(R.string.chat_default_file_name) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = DocumentIngest.extractText(this@ChatActivity, uri)
                    DocumentIngest.chunk(text)
                }
            }
            result
                .onSuccess { chunks ->
                    container.rememberDocument(name, chunks, conversationId)
                    container.settings.memoryEnabled = true
                    if (name !in sessionDocumentNames) sessionDocumentNames += name
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
        // CloudProvider.visionCapable answers this correctly for a cloud
        // provider (a fixed property of the API), but local's own vision
        // support isn't fixed at all — it depends on which specific model
        // is installed and whether its projector downloaded (see
        // AppContainer.localVisionAvailable's own comment). Checking the
        // static flag for "local" here meant this refused an image outright
        // — "none of the enabled models understands images" — for a model
        // that, in fact, did.
        val visionAvailable = container.settings.enabledProviderIds.any { id ->
            if (id == CloudProviders.LOCAL.id) container.localVisionAvailable() else CloudProviders.byId(id).visionCapable
        }
        if (!visionAvailable) {
            // A dialog, not a Toast: this explains *why* and what to do
            // about it, and a Toast — gone in a few seconds regardless of
            // length, easy to miss entirely if the message is mid-scroll or
            // the keyboard just opened — isn't something a user can go back
            // and actually read once it's dismissed itself.
            AlertDialog.Builder(this)
                .setTitle(R.string.chat_attach_image_no_vision_title)
                .setMessage(R.string.chat_attach_image_no_vision)
                .setPositiveButton(R.string.dialog_ok, null)
                .show()
            return
        }
        lifecycleScope.launch {
            val name = DocumentIngest.fileName(this@ChatActivity, uri).ifBlank { getString(R.string.chat_default_image_name) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val originalBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException(getString(R.string.error_could_not_open_file))
                    if (originalBytes.size > MAX_IMAGE_BYTES) {
                        throw java.io.IOException(
                            getString(
                                R.string.chat_attach_image_too_large,
                                getString(R.string.unit_mb, "%.1f".format(Locale.US, originalBytes.size / 1_000_000.0)),
                            ),
                        )
                    }

                    // BitmapFactory ignores the orientation tag entirely — it
                    // decodes raw pixels as stored, not as the photo should be
                    // viewed. A phone camera routinely saves landscape byte
                    // order with a rotate-90 tag rather than pre-rotated
                    // pixels, so skipping this would silently hand the model
                    // (and the sent-message thumbnail) a sideways photo.
                    val rotationDegrees = runCatching {
                        ExifInterface(java.io.ByteArrayInputStream(originalBytes)).rotationDegrees
                    }.getOrDefault(0)

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / (sampleSize * 2) >= MAX_IMAGE_DIMENSION ||
                        bounds.outHeight / (sampleSize * 2) >= MAX_IMAGE_DIMENSION
                    ) {
                        sampleSize *= 2
                    }
                    val decoded = BitmapFactory.decodeByteArray(
                        originalBytes, 0, originalBytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize },
                    ) ?: throw java.io.IOException(getString(R.string.error_could_not_decode_image))

                    val oriented = if (rotationDegrees != 0) {
                        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                            .also { if (it !== decoded) decoded.recycle() }
                    } else decoded

                    val longestSide = maxOf(oriented.width, oriented.height)
                    val scaled = if (longestSide > MAX_IMAGE_DIMENSION) {
                        val scale = MAX_IMAGE_DIMENSION.toFloat() / longestSide
                        Bitmap.createScaledBitmap(
                            oriented,
                            (oriented.width * scale).toInt().coerceAtLeast(1),
                            (oriented.height * scale).toInt().coerceAtLeast(1),
                            true,
                        ).also { if (it !== oriented) oriented.recycle() }
                    } else oriented
                    val width = scaled.width
                    val height = scaled.height

                    val jpegBytes = java.io.ByteArrayOutputStream().use { stream ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, stream)
                        stream.toByteArray()
                    }
                    scaled.recycle()

                    // A content:// URI means nothing outside this process —
                    // core/openai are plain JVM with no Context to resolve
                    // it, so the image is embedded as a self-contained data
                    // URI right here rather than threading Android-specific
                    // access down through the runtime layer. Downscaled
                    // first: a local vision model's encode cost scales with
                    // resolution — a raw phone-camera photo (several
                    // megapixels, and some vision models tile a large image
                    // into several crops before encoding) turned a single
                    // image turn into a multi-minute, largely uncancellable
                    // native call on a real device. The same downscale also
                    // shrinks a cloud upload for no quality most vision APIs
                    // would keep anyway — they downscale server-side too.
                    val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    ImageRef(uri = "data:image/jpeg;base64,$base64", widthPx = width, heightPx = height)
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
     * [AudioRecorder.shouldFinalize] reports the documented pause (2s of
     * silence after speech) or the 25s window filling up, so a turn does not
     * require tapping stop at all if the pause is left to do it.
     */
    private fun finalizeRecording() {
        previewJob?.cancel()
        previewJob = null
        // Tiny is done for this recording the moment it stops — freed here
        // rather than left resident until the next one, for the same reason
        // the main engine is freed below.
        container.whisperPreviewEngine.release()
        val audio = recorder.stop()
        binding.micButton.setIconResource(R.drawable.ic_mic)
        updateStatus()
        val seed = container.whisperStore.installedSeed(container.settings.whisperModelId) ?: return

        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.chat_transcribing)
            val result = withContext(Dispatchers.Default) {
                runCatching { container.whisperEngine.transcribe(seed, audio) }
            }
            // Freed immediately after this one transcription, not kept warm
            // for next time: the very next thing that happens is usually
            // Send, which loads (or already has loaded) a local LLM — and a
            // multi-hundred-MB-to-GB Whisper model sitting resident at the
            // same time is exactly the kind of memory pressure a native
            // crash in llama.cpp looks like from the outside, with no
            // exception and no clean error to explain it. Costs a reload
            // (from disk, a second or so for Turbo) on the next recording;
            // worth it over risking that.
            container.whisperEngine.release()
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

        // How often a streaming answer's bubble is allowed to re-render —
        // see the renderJob comments in send()/sendCompare(). Fast enough
        // that streaming still reads as continuous, slow enough that it
        // never competes for the main thread with something as latency-
        // sensitive as the Stop button's own click.
        const val RENDER_INTERVAL_MS = 150L

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

        // Longest side after downscaling. Generous relative to what a vision
        // model actually reads at — cloud APIs downscale to roughly this
        // range server-side already, and llama.cpp's mtmd encoders (Gemma's
        // included) operate on inputs well under this — while still far
        // below a modern phone camera's native resolution, which is what
        // was turning a single attached photo into a multi-minute vision
        // encode on-device (see llama_jni.cpp's nativeGenerateWithImage).
        const val MAX_IMAGE_DIMENSION = 1280

        const val IMAGE_JPEG_QUALITY = 85

        // Short enough to read as "live", long enough that Tiny is done
        // transcribing everything so far well before the next tick.
        // Matches UtteranceConfig.refreshMs (docs/12-audio.md) — a validated
        // number from a working implementation, not a guess.
        const val PREVIEW_INTERVAL_MS = 2_000L
        const val PREVIEW_FIRST_DELAY_MS = 700L
    }
}
