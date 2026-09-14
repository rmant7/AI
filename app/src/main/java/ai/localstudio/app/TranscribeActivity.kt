package ai.localstudio.app

import ai.localstudio.app.databinding.ActivityTranscribeBinding
import ai.localstudio.app.databinding.ItemTranscribeResultBinding
import ai.localstudio.app.whisper.MediaFileUtils
import ai.localstudio.app.whisper.WhisperCppRuntime
import ai.localstudio.app.whisper.WhisperFileTranscriber
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.core.model.TranscriptSegment
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Test harness for the whisper.cpp vertical slice
 * (docs/13-asr-pipeline-migration.md): pick a file or a folder, transcribe
 * it through [WhisperFileTranscriber] (which loads the model once and reuses
 * it across every file), watch segments arrive before the file finishes
 * decoding, save each result as it completes.
 *
 * Deliberately not wired through [ai.localstudio.core.engine.Orchestrator] —
 * this is a way to exercise [WhisperCppRuntime]/[ai.localstudio.app.whisper.WhisperCppSpeechModel]
 * directly, the same way [ChatActivity]'s mic button already talks to
 * `WhisperEngine` directly rather than through the router.
 */
class TranscribeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscribeBinding
    private lateinit var container: AppContainer
    private lateinit var transcriber: WhisperFileTranscriber
    private val adapter = ResultAdapter()
    private val results = mutableListOf<Result>()
    private var job: Job? = null

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
        transcriber = WhisperFileTranscriber(container.whisperCppRuntime as WhisperCppRuntime, container.whisperStore)

        binding.transcribeResults.layoutManager = LinearLayoutManager(this)
        binding.transcribeResults.adapter = adapter

        binding.pickFileButton.setOnClickListener { pickFileLauncher.launch(arrayOf("audio/*", "video/*")) }
        binding.pickFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.transcribeStartButton.setOnClickListener { start() }
        binding.transcribeStopButton.setOnClickListener { stop() }

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Batch lifecycle, not per-file: the model stays loaded across every
        // file in this run and is only released when this screen is actually
        // done, matching docs/13-asr-pipeline-migration.md's "load once" rule.
        transcriber.release()
    }

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

    private fun start() {
        val seed = container.whisperStore.installedSeed()
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
            val transcript = transcriber.transcribe(result.uri, seed, language = null) { segment: TranscriptSegment ->
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
            throw e
        } catch (e: Exception) {
            result.status = Status.ERROR
            result.error = e.message ?: e.toString()
        }
        render()
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
        transcriber.requestCancel()
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
                    Status.CANCELLED -> getString(R.string.transcribe_status_cancelled)
                }
                binding.resultText.visibility = if (result.text.isBlank()) View.GONE else View.VISIBLE
                binding.resultText.text = result.text
            }
        }
    }
}
