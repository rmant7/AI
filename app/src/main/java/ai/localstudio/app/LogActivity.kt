package ai.localstudio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivityLogBinding
import ai.localstudio.app.llama.LlamaBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Read-only view of [ai.localstudio.app.log.AppLog] — the whole point is
 * getting a report of what went wrong OFF the phone: one tap copies
 * everything to the clipboard so it can be pasted wherever it needs to go,
 * without anyone having to plug the phone into a computer and pull logcat.
 *
 * Reachable straight from the chat's own overflow menu, not buried in
 * Settings: this is the screen actually used mid-conversation, right after
 * something goes wrong, not a place someone browses to on a calm day.
 *
 * The build/device header (moved here from Settings' old "About this
 * build" dialog, which is gone) is folded into the same [copyLog] — every
 * copied report carries the exact commit and CI build it came from, so a
 * log pasted into chat is never ambiguous about which build produced it.
 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)

        binding.logCopyButton.setOnClickListener { copyLog() }
        binding.logClearButton.setOnClickListener { confirmClear() }

        render()
        // Real device report: this screen rendered once, in onCreate, and
        // never again — someone watching it during a long-running benchmark
        // saw a frozen snapshot from the moment they opened it, while new
        // lines kept landing in the underlying log file the whole time,
        // completely invisible until the screen was closed and reopened.
        // A plain periodic poll rather than a reactive AppLog.record() Flow:
        // this is a read-only diagnostic screen, not a latency-sensitive
        // one, and readAll() re-reading a small text file every few seconds
        // is cheap enough not to need the extra plumbing.
        lifecycleScope.launch {
            while (isActive) {
                delay(2_000)
                render()
            }
        }
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

    private var lastRenderedLog: String? = null
    private var lastRenderedHeader: String? = null

    /**
     * Real device report: with a long log, selecting just its last part to
     * copy was impossible — the view kept jumping back to the very top.
     * This used to re-set both TextViews every 2 s whether anything had
     * changed or not; on a selectable TextView that drops the selection and
     * resets the cursor to position 0, which the ScrollView then scrolls
     * into view. Now: nothing is touched while a selection exists, text is
     * only re-set when it actually changed, and the scroll position is
     * restored afterwards — following the bottom if that's where the reader
     * was (and on first open, so the newest lines are what's on screen).
     */
    private fun render() {
        if (binding.logText.hasSelection() || binding.logHeader.hasSelection()) return
        val header = buildHeader()
        val log = container.appLog.readAll().ifBlank { getString(R.string.log_empty) }
        if (header == lastRenderedHeader && log == lastRenderedLog) return

        val scroll = binding.logScroll
        val content = scroll.getChildAt(0)
        val firstRender = lastRenderedLog == null
        val wasAtBottom = firstRender || scroll.scrollY + scroll.height >= content.height - FOLLOW_BOTTOM_SLOP_PX
        val keptScrollY = scroll.scrollY

        val logChanged = log != lastRenderedLog
        if (header != lastRenderedHeader) binding.logHeader.text = header
        if (logChanged) binding.logText.text = log
        lastRenderedHeader = header
        lastRenderedLog = log

        // Only after the log itself changed: the header's RAM figure changes
        // every tick, and snapping the scroll back on each of those would
        // fight a fling the reader is in the middle of.
        if (logChanged) scroll.post { scroll.scrollTo(0, if (wasAtBottom) content.height else keptScrollY) }
    }

    private fun copyLog() {
        val log = container.appLog.readAll()
        if (log.isBlank()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val report = buildHeader() + "\n\n" + newest(log)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Local AI Studio log", report))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * The newest [COPY_RECENT_CHARS] of [log], whole lines, saying what it
     * left out: a report is pasted into a chat, and a chat cuts a long paste
     * at its end -- the newest entries, the ones that matter (IntelliVerse
     * #163's log arrived twice cut mid-line at the same spot).
     */
    private fun newest(log: String): String {
        if (log.length <= COPY_RECENT_CHARS) return log
        val tail = log.takeLast(COPY_RECENT_CHARS)
        val fromLine = tail.substringAfter('\n', tail)
        val omittedLines = log.substring(0, log.length - fromLine.length).count { it == '\n' }
        return "[$omittedLines earlier lines left out]\n$fromLine"
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                container.appLog.clear()
                lastRenderedLog = null
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * `llama_print_system_info()` only formats compile-time flags — cheap
     * for a single call, including the native library's first
     * [System.loadLibrary]. But [render] now re-runs every 2s (see this
     * class's own doc comment on why), and these flags never change once
     * the process is up — real device report: re-querying it on every
     * single poll, on the main thread, added up to visible stutter/freezes
     * across the whole app while a benchmark was simultaneously saturating
     * the CPU with whisper.cpp inference. Computed once, lazily, instead.
     */
    private val cpuLine: String by lazy {
        val cpuInfo = if (LlamaBridge.isAvailable) runCatching { LlamaBridge().nativeSystemInfo() }.getOrNull() else null
        val features = if (cpuInfo.isNullOrBlank()) getString(R.string.log_cpu_unavailable) else getString(R.string.log_cpu_features, cpuInfo)
        // Which of the three CPU-feature builds this device got — the features
        // line above describes that build's compile flags, not the CPU itself.
        "$features\nCPU variant: ${ai.localstudio.core.runtime.CpuVariant.current} · loaded: ${LlamaBridge.loadedLibrary ?: "none"}"
    }

    /**
     * Version/commit/CI build, device model/OS/ABI, RAM, and whether
     * llama.cpp actually detected this CPU's dotprod/i8mm/fp16 support —
     * everything that used to require opening a separate "About this
     * build" dialog before comparing it against a log.
     */
    private fun buildHeader(): String {
        val device = container.device
        val buildLine = getString(
            R.string.log_build_line,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_SHA,
            BuildConfig.CI_RUN,
        )
        val deviceLine = getString(
            R.string.log_device_line,
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
        val ramLine = getString(R.string.device_ram_line, gb(device.totalRamBytes), gb(device.availableRamBytes))
        return listOf(buildLine, deviceLine, ramLine, cpuLine).joinToString("\n")
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)

    private companion object {
        /** How close to the bottom still counts as "following the newest lines". */
        const val FOLLOW_BOTTOM_SLOP_PX = 48

        /** What fits a chat message with room to spare. */
        const val COPY_RECENT_CHARS = 12_000
    }
}
