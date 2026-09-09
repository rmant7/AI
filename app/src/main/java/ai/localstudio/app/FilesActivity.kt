package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.attach.AttachedDocument
import ai.localstudio.app.databinding.ActivityFilesBinding
import ai.localstudio.app.databinding.ItemFileBinding
import ai.localstudio.app.databinding.ItemFilesHeaderBinding
import ai.localstudio.app.history.ChatHistoryStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers what the attach button in chat never explained: where an uploaded
 * file went, and how to get rid of it.
 *
 * Grouped by the chat each file was attached in — its content is force-
 * included specifically there (see NodeExecutors.contextBuild); a chat that
 * never attached it can still surface it through ordinary memory search, but
 * only if the question happens to share words with the file's content. A
 * flat, chat-agnostic list here would have implied the opposite: that every
 * file is equally "in" every chat, which stopped being true the moment
 * force-inclusion became something worth doing at all.
 */
class FilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilesBinding
    private lateinit var container: AppContainer
    private lateinit var history: ChatHistoryStore
    private val adapter = RowAdapter(::onView, ::onDelete)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)
        history = ChatHistoryStore(this)
        binding.files.layoutManager = LinearLayoutManager(this)
        binding.files.adapter = adapter

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun onView(document: AttachedDocument) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.files_view_title, document.name))
            .setMessage(document.chunks.joinToString("\n\n"))
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun onDelete(document: AttachedDocument) {
        lifecycleScope.launch {
            container.forgetDocument(document.id)
            render()
        }
    }

    private fun render() {
        val documents = container.documents.list()
        binding.filesEmpty.visibility = if (documents.isEmpty()) View.VISIBLE else View.GONE
        binding.files.visibility = if (documents.isEmpty()) View.GONE else View.VISIBLE

        // Groups keep documents.list()'s own order (newest first) — the
        // group a document's most recent file belongs to sorts first, same
        // idea HistoryActivity uses for conversations themselves.
        val rows = buildList {
            documents.groupBy { it.conversationId }
                .toList()
                .sortedByDescending { (_, docs) -> docs.maxOf { it.addedAt } }
                .forEach { (conversationId, docs) ->
                    add(Row.Header(titleFor(conversationId)))
                    docs.forEach { add(Row.File(it)) }
                }
        }
        adapter.submit(rows)
    }

    private fun titleFor(conversationId: String): String = when {
        conversationId == AttachedDocument.UNKNOWN_CONVERSATION -> getString(R.string.files_unknown_chat)
        else -> history.load(conversationId)?.displayTitle ?: getString(R.string.files_deleted_chat)
    }

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class File(val document: AttachedDocument) : Row
    }

    private class RowAdapter(
        private val onView: (AttachedDocument) -> Unit,
        private val onDelete: (AttachedDocument) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.File -> TYPE_FILE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemFilesHeaderBinding.inflate(inflater, parent, false))
            } else {
                FileHolder(ItemFileBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.title)
                is Row.File -> (holder as FileHolder).bind(row.document, onView, onDelete)
            }
        }

        private class HeaderHolder(val binding: ItemFilesHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(title: String) {
                binding.root.text = title
            }
        }

        private class FileHolder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
            private val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            fun bind(document: AttachedDocument, onView: (AttachedDocument) -> Unit, onDelete: (AttachedDocument) -> Unit) {
                binding.fileTitle.text = document.name
                binding.fileSubtitle.text = binding.root.context.getString(
                    R.string.files_subtitle,
                    document.chunkCount,
                    format.format(Date(document.addedAt)),
                )
                (binding.fileTitle.parent as View).setOnClickListener { onView(document) }
                binding.fileDeleteButton.setOnClickListener { onDelete(document) }
            }
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_FILE = 1
        }
    }
}
