package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.attach.AttachedDocument
import ai.localstudio.app.databinding.ActivityFilesBinding
import ai.localstudio.app.databinding.ItemFileBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Answers what the attach button in chat never explained: where an uploaded
 * file went, and how to get rid of it. Everything shown here is memory the
 * whole app shares — the hint text at the top says so up front, rather than
 * let someone discover it by noticing a different chat already knows about
 * a file.
 */
class FilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilesBinding
    private lateinit var container: AppContainer
    private val adapter = FileAdapter(::onDelete)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)
        binding.files.layoutManager = LinearLayoutManager(this)
        binding.files.adapter = adapter

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
        adapter.submit(documents)
    }

    private class FileAdapter(
        private val onDelete: (AttachedDocument) -> Unit,
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {

        private var documents: List<AttachedDocument> = emptyList()

        fun submit(next: List<AttachedDocument>) {
            documents = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = documents.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(documents[position], onDelete)
        }

        class Holder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root) {
            private val format = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

            fun bind(document: AttachedDocument, onDelete: (AttachedDocument) -> Unit) {
                binding.fileTitle.text = document.name
                binding.fileSubtitle.text = binding.root.context.getString(
                    R.string.files_subtitle,
                    document.chunkCount,
                    format.format(Date(document.addedAt)),
                )
                binding.fileDeleteButton.setOnClickListener { onDelete(document) }
            }
        }
    }
}
