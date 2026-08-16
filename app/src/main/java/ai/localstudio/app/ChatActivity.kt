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
import ai.localstudio.app.camera.CameraActivity
import ai.localstudio.app.databinding.ActivityChatBinding
import ai.localstudio.app.history.ChatHistoryStore
import ai.localstudio.app.history.Conversation
import ai.localstudio.app.history.toMessage
import ai.localstudio.app.history.toStored
import ai.localstudio.app.whisper.AudioRecorder
import ai.localstudio.core.engine.UserRequest
import ai.localstudio.core.memory.MemoryScope
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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        container.whisperEngine.release()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MEMORY, 0, memoryTitle()).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MODELS, 1, R.string.menu_models)
        menu.add(0, MENU_CAMERA, 2, R.string.menu_camera)
        menu.add(0, MENU_SETTINGS, 3, R.string.menu_settings)
        menu.add(0, MENU_HISTORY, 4, R.string.menu_history)
        menu.add(0, MENU_CLEAR, 5, R.string.menu_clear)
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

        MENU_CAMERA -> {
            startActivity(Intent(this, CameraActivity::class.java))
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

        else -> super.onOptionsItemSelected(item)
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
        binding.statusText.text = "${container.settings.chatModel} · ${container.runtimeLabel} · $memory"
    }

    private fun send() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        binding.input.setText("")
        adapter.add(Message.user(text))
        binding.messages.scrollToPosition(adapter.itemCount - 1)
        persist()
        setBusy(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.orchestrator().handle(
                        UserRequest(
                            conversationId = conversationId,
                            text = text,
                            memoryEnabled = container.settings.memoryEnabled,
                        ),
                    )
                }
            }
            setBusy(false)
            result
                .onSuccess { answer ->
                    adapter.add(
                        Message.assistant(
                            body = answer.text.ifBlank { "(пустой ответ)" },
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
                    adapter.add(
                        Message.error(
                            body = error.message ?: error.toString(),
                            details = error.javaClass.simpleName,
                        ),
                    )
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
                    chunks.forEach { chunk ->
                        container.memory.remember(chunk, MemoryScope.SEMANTIC, metadata = mapOf("source" to name))
                    }
                    container.settings.memoryEnabled = true
                    invalidateOptionsMenu()
                    updateStatus()
                    Toast.makeText(this@ChatActivity, getString(R.string.chat_attach_added, name, chunks.size), Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
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
            val seed = container.whisperStore.installedSeed()
            if (seed == null) {
                Toast.makeText(this, R.string.chat_mic_no_model, Toast.LENGTH_LONG).show()
                return
            }
            recorder.start()
            binding.micButton.text = "■"
            binding.statusText.text = getString(R.string.chat_recording)
            return
        }

        val audio = recorder.stop()
        binding.micButton.text = getString(R.string.chat_mic)
        updateStatus()
        val seed = container.whisperStore.installedSeed() ?: return

        lifecycleScope.launch {
            binding.statusText.text = getString(R.string.chat_transcribing)
            val result = withContext(Dispatchers.Default) {
                runCatching { container.whisperEngine.transcribe(seed, audio) }
            }
            updateStatus()
            result
                .onSuccess { text ->
                    if (text.isNotBlank()) {
                        val current = binding.input.text?.toString().orEmpty()
                        binding.input.setText(if (current.isBlank()) text else "$current $text")
                        binding.input.setSelection(binding.input.text?.length ?: 0)
                    }
                }
                .onFailure { error ->
                    Toast.makeText(this@ChatActivity, error.message ?: error.toString(), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendButton.isEnabled = !busy
    }

    private companion object {
        const val MENU_MEMORY = 1
        const val MENU_MODELS = 2
        const val MENU_CAMERA = 3
        const val MENU_SETTINGS = 4
        const val MENU_HISTORY = 5
        const val MENU_CLEAR = 6
    }
}
