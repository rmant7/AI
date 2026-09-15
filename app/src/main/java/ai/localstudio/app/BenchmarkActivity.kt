package ai.localstudio.app

import ai.localstudio.app.benchmark.BenchmarkFileScanner
import ai.localstudio.app.benchmark.BenchmarkPerformanceMode
import ai.localstudio.app.benchmark.SustainedPerformanceSupport
import ai.localstudio.app.databinding.ActivityBenchmarkBinding
import ai.localstudio.core.benchmark.BenchmarkAudioFile
import ai.localstudio.core.benchmark.BenchmarkReport
import ai.localstudio.core.benchmark.BenchmarkSummary
import ai.localstudio.core.benchmark.BenchmarkSummaryRow
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
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
 *
 * Three performance modes ([BenchmarkPerformanceMode]) exist to answer one
 * question: does Android's Sustained Performance Mode actually produce a
 * higher *total* throughput on a long inference series by avoiding heavy
 * thermal throttling, versus just running flat-out (MAXIMUM) or pausing to
 * let the device cool between engines (COOL_DOWN). MAXIMUM and SUSTAINED
 * are otherwise identical runs — same engines, same files, same timeouts —
 * specifically so they're a valid comparison.
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

    /**
     * `Window.setSustainedPerformanceMode` only has any effect while this
     * exact [android.view.Window] is focused — it cannot be set from
     * [AppContainer.benchmarkOrchestrator] itself, which runs the benchmark
     * independent of any Activity (see that class's own doc comment on
     * why). Re-asserted here, not just once from [render], because some
     * OEMs clear window-level flags across a pause/resume cycle even when
     * the Activity itself isn't destroyed.
     */
    override fun onResume() {
        super.onResume()
        applySustainedWindowFlag()
    }

    override fun onPause() {
        super.onPause()
        window.setSustainedPerformanceMode(false)
    }

    private fun applySustainedWindowFlag() {
        val state = container.benchmarkOrchestrator.state.value
        val active = state is BenchmarkUiState.Running &&
            state.mode == BenchmarkPerformanceMode.SUSTAINED &&
            SustainedPerformanceSupport.isSupported(this)
        window.setSustainedPerformanceMode(active)
    }

    private fun selectedMode(): BenchmarkPerformanceMode = when (binding.benchmarkModeGroup.checkedRadioButtonId) {
        binding.benchmarkModeSustained.id -> BenchmarkPerformanceMode.SUSTAINED
        binding.benchmarkModeCoolDown.id -> BenchmarkPerformanceMode.COOL_DOWN
        else -> BenchmarkPerformanceMode.MAXIMUM
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        UtilityMenu.inflate(this, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        UtilityMenu.handle(this, item.itemId) || super.onOptionsItemSelected(item)

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
        binding.benchmarkTrendText.visibility = View.GONE
        container.benchmarkOrchestrator.start(scannedFiles, selectedMode())
    }

    private fun render(state: BenchmarkUiState) {
        applySustainedWindowFlag()
        when (state) {
            is BenchmarkUiState.Idle -> {
                binding.benchmarkProgress.visibility = View.GONE
                binding.benchmarkCancelButton.isEnabled = false
                binding.benchmarkStartButton.isEnabled = scannedFiles.isNotEmpty()
                binding.benchmarkPickFolderButton.isEnabled = true
                setModePickerEnabled(true)
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
                setModePickerEnabled(false)
            }
            is BenchmarkUiState.Done -> {
                binding.benchmarkProgress.visibility = View.GONE
                binding.benchmarkCancelButton.isEnabled = false
                binding.benchmarkStartButton.isEnabled = scannedFiles.isNotEmpty()
                binding.benchmarkPickFolderButton.isEnabled = true
                setModePickerEnabled(true)
                binding.benchmarkStatusText.text = getString(R.string.benchmark_done, state.savedAs) +
                    if (state.report.performanceMode == BenchmarkPerformanceMode.SUSTAINED.name && !state.report.sustainedModeSupported) {
                        "\n" + getString(R.string.benchmark_sustained_unsupported)
                    } else {
                        ""
                    }
                lastReport = state.report
                renderSummary(state.report)
                renderTrend(state.report)
                binding.benchmarkShareButton.visibility = View.VISIBLE
            }
            is BenchmarkUiState.Failed -> {
                binding.benchmarkProgress.visibility = View.GONE
                binding.benchmarkCancelButton.isEnabled = false
                binding.benchmarkStartButton.isEnabled = scannedFiles.isNotEmpty()
                binding.benchmarkPickFolderButton.isEnabled = true
                setModePickerEnabled(true)
                binding.benchmarkStatusText.text = getString(R.string.benchmark_failed, state.message)
            }
        }
    }

    private fun setModePickerEnabled(enabled: Boolean) {
        binding.benchmarkModeMaximum.isEnabled = enabled
        binding.benchmarkModeSustained.isEnabled = enabled
        binding.benchmarkModeCoolDown.isEnabled = enabled
    }

    /** Requirement: first-run/average/last-run RTF and the degradation % between them — see [BenchmarkSummary.computeTrend]'s own doc comment for why this needs the run's execution order, not [report]'s own file-grouped structure. */
    private fun renderTrend(report: BenchmarkReport) {
        val trend = report.performanceTrend
        val first = trend?.firstRtf
        if (trend == null || first == null) {
            binding.benchmarkTrendText.visibility = View.GONE
            return
        }
        binding.benchmarkTrendText.text = getString(
            R.string.benchmark_trend,
            "%.3f".format(Locale.US, first),
            trend.averageRtf?.let { "%.3f".format(Locale.US, it) } ?: "—",
            trend.lastRtf?.let { "%.3f".format(Locale.US, it) } ?: "—",
            trend.degradationPercent?.let { "%.1f%%".format(Locale.US, it) } ?: "—",
        )
        binding.benchmarkTrendText.visibility = View.VISIBLE
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
