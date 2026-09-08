package ai.localstudio.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityHistoryBinding
import ai.localstudio.app.databinding.ItemConversationBinding
import ai.localstudio.app.history.ChatHistoryStore
import ai.localstudio.app.history.Conversation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saved chats, with the two things a list of them is for: opening one, and
 * getting rid of one.
 *
 * This replaces a pair of stacked dialogs. Deleting used to mean opening the
 * history dialog, pressing what looked like its cancel button — "Удалить"
 * sat in the negative-button slot — and then picking from a second,
 * identical-looking list; renaming was not possible at all. Both are row
 * actions here, next to the chat they act on.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var history: ChatHistoryStore
    private val adapter = ConversationAdapter(::open, ::rename, ::confirmDelete)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.menu_history)

        history = ChatHistoryStore(this)
        binding.conversations.layoutManager = LinearLayoutManager(this)
        binding.conversations.adapter = adapter

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        val conversations = history.list()
        binding.historyEmpty.visibility = if (conversations.isEmpty()) View.VISIBLE else View.GONE
        binding.conversations.visibility = if (conversations.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(conversations)
    }

    /** Hands the id back to the chat screen, which owns loading it. */
    private fun open(conversation: Conversation) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CONVERSATION_ID, conversation.id))
        finish()
    }

    private fun rename(conversation: Conversation) {
        val input = EditText(this).apply {
            setText(conversation.displayTitle)
            setHint(R.string.history_rename_hint)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.history_rename)
            .setView(input)
            .setPositiveButton(R.string.history_save) { _, _ ->
                history.rename(conversation.id, input.text.toString())
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Asked before deleting, unlike the flow this replaces: the messages go
     * with it, and nothing here can bring them back.
     */
    private fun confirmDelete(conversation: Conversation) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.history_delete_confirm, conversation.displayTitle))
            .setPositiveButton(R.string.history_delete) { _, _ ->
                history.delete(conversation.id)
                // Told about it even when it is not the chat on screen: the
                // chat screen has to drop the conversation it is holding if
                // this was it, rather than carry on writing to a file that
                // no longer exists.
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DELETED_ID, conversation.id))
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private class ConversationAdapter(
        private val onOpen: (Conversation) -> Unit,
        private val onRename: (Conversation) -> Unit,
        private val onDelete: (Conversation) -> Unit,
    ) : RecyclerView.Adapter<ConversationAdapter.Holder>() {

        private var conversations: List<Conversation> = emptyList()

        fun submit(next: List<Conversation>) {
            conversations = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = conversations.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(conversations[position], onOpen, onRename, onDelete)
        }

        class Holder(val binding: ItemConversationBinding) : RecyclerView.ViewHolder(binding.root) {
            private val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            fun bind(
                conversation: Conversation,
                onOpen: (Conversation) -> Unit,
                onRename: (Conversation) -> Unit,
                onDelete: (Conversation) -> Unit,
            ) {
                binding.conversationTitle.text = conversation.displayTitle
                binding.conversationSubtitle.text = binding.root.context.getString(
                    R.string.history_subtitle,
                    conversation.messages.size,
                    format.format(Date(conversation.updatedAt)),
                )
                binding.root.setOnClickListener { onOpen(conversation) }
                binding.conversationRenameButton.setOnClickListener { onRename(conversation) }
                binding.conversationDeleteButton.setOnClickListener { onDelete(conversation) }
            }
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_DELETED_ID = "deletedConversationId"

        fun intent(context: Context): Intent = Intent(context, HistoryActivity::class.java)
    }
}
