package ai.localstudio.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.localstudio.app.databinding.ActivityChatBinding
import ai.localstudio.core.engine.UserRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var container: AppContainer
    private val adapter = MessageAdapter()
    private val conversationId = "chat-" + System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        container = AppContainer.get(this)
        binding.root.applySystemBarInsets(applyImeInset = true)

        binding.messages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messages.adapter = adapter

        binding.sendButton.setOnClickListener { send() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_MEMORY, 0, memoryTitle()).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_MODELS, 1, R.string.menu_models)
        menu.add(0, MENU_SETTINGS, 2, R.string.menu_settings)
        menu.add(0, MENU_CLEAR, 3, R.string.menu_clear)
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

        MENU_MEMORY -> {
            container.settings.memoryEnabled = !container.settings.memoryEnabled
            invalidateOptionsMenu()
            updateStatus()
            true
        }

        MENU_CLEAR -> {
            adapter.clear()
            true
        }

        else -> super.onOptionsItemSelected(item)
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
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendButton.isEnabled = !busy
    }

    private companion object {
        const val MENU_MEMORY = 1
        const val MENU_MODELS = 2
        const val MENU_SETTINGS = 3
        const val MENU_CLEAR = 4
    }
}
