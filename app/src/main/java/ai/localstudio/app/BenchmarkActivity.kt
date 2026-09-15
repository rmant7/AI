package ai.localstudio.app

import ai.localstudio.app.benchmark.BenchmarkFileScanner
import ai.localstudio.app.databinding.ActivityBenchmarkBinding
import ai.localstudio.core.benchmark.BenchmarkAudioFile
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkSummary
import ai.localstudio.core.benchmark.BenchmarkSummaryRow
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import ai.localstudio.app.benchmark.BenchmarkUiState

/**
 * "STT Benchmark" (see docs/16-stt-benchmark.md): pick a folder, scan it for
 * supported audio, run every registered [ai.localstudio.core.benchmark.TranscriptionEngine]
 * against every file, show a summary table. Deliberately not wired through
 * [TranscribeActivity]'s own screen — comparing backends objectively is a
 * distinct task from that screen's manual "pick a file, transcribe it"
 * workflow, and mixing the two would make neither UI simple.
 *
 * The run itself lives in [AppContainer.benchmarkOrchestrator], not here —
 * same reasoning [AppContainer.fileTranscriptionRunner] already
 * established (see that class's own doc comment): a benchmark over
 * hundreds of files across several engines can run far longer than this
 * Activity is guaranteed to stay alive for.
 */
class BenchmarkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBenchmarkBinding
    private lateinit var container: AppContainer
    private var scannedFiles: List<BenchmarkAudioFile> = emptyList()
    private var lastReport: BenchmarkReport? = null

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scanFolder(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBenchmarkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.menu_benchmark)

        container = AppContainer.get(this)

        binding.benchmarkPickFolderButton.setOnClickListener { pickFolderLauncher.launch(null) }
        binding.benchmarkStartButton.setOnClickListener { startBenchmark() }
        binding.benchmarkCancelButton.setOnClickListener { container.benchmarkOrchestrator.cancel() }
        binding.benchmarkShareButton.setOnClickListener { shareReport() }

        lifecycleScope.launch {
            container.benchmarkOrchestrator.state.collect { state -> render(state) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun scanFolder(uri: Uri) {
        val tree = DocumentFile.fromTreeUri(this, uri) ?: return
        binding.benchmarkSourceText.text = getString(R.string.benchmark_scanning)
        binding.benchmarkStartButton.isEnabled = false
        lifecycleScope.launch {
            // Same lesson as TranscribeActivity's own folder pick: scanning
            // (here, also reading per-file audio metadata via MediaExtractor)
            // is real I/O over potentially hundreds of files — never do it
            // in-line on the callback's own main thread.
            val files = withContext(Dispatchers.IO) { BenchmarkFileScanner.scan(this@BenchmarkActivity, tree) }
            scannedFiles = files
            if (files.isEmpty()) {
                binding.benchmarkSourceText.text = getString(R.string.benchmark_no_files)
                return@launch
            }
            val totalDurationMs = files.sumOf { it.durationMs ?: 0L }
            val totalSizeBytes = files.sumOf { it.fileSizeBytes }
            binding.benchmarkSourceText.text = getString(
                R.string.benchmark_files_found,
                files.size,
                formatDuration(totalDurationMs),
                formatBytes(totalSizeBytes),
            )
            binding.benchmarkStartButton.isEnabled = true
        }
    }

    private fun startBenchmark() {
        if (scannedFiles.isEmpty()) return
        if (container.transcriptionEngines.isEmpty()) {
            Toast.makeText(this, R.string.benchmark_no_engines, Toast.LENGTH_LONG).show()
            return
        }
        binding.benchmarkSummaryTable.visibility = View.GONE
        binding.benchmarkShareButton.visibility = View.GONE
        container.benchmarkOrchestrator.start(scannedFiles)
    }

    private fun render(state: BenchmarkUiState) {
        when (state) {
            is BenchmarkUiState.Idle -> {
                binding.benchmarkProgress.visibility = View.GONE
                binding.benchmarkCancelButton.isEnabled = false
                binding.benchmarkStartButton.isEnabled = scannedFiles.isNotEmpty()
                binding.benchmarkPickFolderButton.isEnabled = true
            }
            is BenchmarkUiState.Running -> {
                binding.benchmarkProgress.visibility = View.VISIBLE
                binding.benchmarkProgress.isIndeterminate = false
                binding.benchmarkProgress.max = state.total.coerceAtLeast(1)
                binding.benchmarkProgress.progress = state.completed
                val counter = getString(R.string.benchmark_running, state.completed, state.total)
                binding.benchmarkStatusText.text = if (state.status.isBlank()) counter else "$counter\n${state.status}"
                binding.benchmarkCancelButton.isEnabled = true
                binding.benchmarkStartButton.isEnabled = false
                binding.benchmarkPickFolderButton.isEnabled = false
            }
            is BenchmarkUiState.Done -> {
                binding.benchmarkProgress.visibility = View.GONE
                binding.benchmarkCancelButton.isEnabled = false
                binding.benchmarkStartButton.isEnabled = scannedFiles.isNotEmpty()
                binding.benchmarkPickFolderButton.isEnabled = true
                binding.benchmarkStatusText.text = getString(R.string.benchmark_done, state.savedAs)
                lastReport = state.report
                renderSummary(state.report)
                binding.benchmarkShareButton.visibility = View.VISIBLE
            }
            is BenchmarkUiState.Failed -> {
                binding.benchmarkProgress.visibility = View.GONE
                binding.benchmarkCancelButton.isEnabled = false
                binding.benchmarkStartButton.isEnabled = scannedFiles.isNotEmpty()
                binding.benchmarkPickFolderButton.isEnabled = true
                binding.benchmarkStatusText.text = getString(R.string.benchmark_failed, state.message)
            }
        }
    }

    private fun renderSummary(report: BenchmarkReport) {
        val table = binding.benchmarkSummaryTable
        table.removeAllViews()
        val rows = BenchmarkSummary.summarize(report)
        table.addView(headerRow())
        rows.forEach { table.addView(dataRow(it)) }
        table.visibility = View.VISIBLE
    }

    private fun headerRow(): TableRow {
        val labels = listOf("Backend", "Model", "Files", "Avg RTF", "Median RTF", "Avg time", "Peak RAM", "Errors")
        return TableRow(this).apply {
            labels.forEach { addView(cell(it, bold = true)) }
        }
    }

    private fun dataRow(row: BenchmarkSummaryRow): TableRow {
        val cells = listOf(
            row.displayName,
            row.modelId,
            row.fileCount.toString(),
            row.avgRtf?.let { "%.3f".format(Locale.US, it) } ?: "—",
            row.medianRtf?.let { "%.3f".format(Locale.US, it) } ?: "—",
            row.avgProcessingMs?.let { "%.1fs".format(Locale.US, it / 1000.0) } ?: "—",
            row.peakMemoryMb?.let { "$it MB" } ?: "—",
            row.errorCount.toString(),
        )
        return TableRow(this).apply {
            cells.forEach { addView(cell(it, bold = false)) }
        }
    }

    private fun cell(text: String, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        gravity = Gravity.START
        setPadding(8, 8, 8, 8)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun shareReport() {
        val report = lastReport ?: return
        val dir = File(filesDir, "benchmarks")
        val file = dir.listFiles()?.filter { it.name.endsWith(".json") }?.maxByOrNull { it.lastModified() } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.transcribe_share)))
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
    }
}
