package ai.localstudio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ai.localstudio.app.databinding.ActivityLogBinding
import ai.localstudio.app.llama.LlamaBridge

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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        binding.logHeader.text = buildHeader()
        val log = container.appLog.readAll()
        binding.logText.text = log.ifBlank { getString(R.string.log_empty) }
    }

    private fun copyLog() {
        val log = container.appLog.readAll()
        if (log.isBlank()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val report = buildHeader() + "\n\n" + log
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Local AI Studio log", report))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.message_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                container.appLog.clear()
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Version/commit/CI build, device model/OS/ABI, RAM, and whether
     * llama.cpp actually detected this CPU's dotprod/i8mm/fp16 support —
     * everything that used to require opening a separate "About this
     * build" dialog before comparing it against a log. Cheap even including
     * the native library's first [System.loadLibrary] call:
     * `llama_print_system_info()` only formats compile-time flags, no model
     * load involved.
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
        val cpuInfo = if (LlamaBridge.isAvailable) runCatching { LlamaBridge().nativeSystemInfo() }.getOrNull() else null
        val cpuLine = if (cpuInfo.isNullOrBlank()) {
            getString(R.string.log_cpu_unavailable)
        } else {
            getString(R.string.log_cpu_features, cpuInfo)
        }
        return listOf(buildLine, deviceLine, ramLine, cpuLine).joinToString("\n")
    }

    private fun gb(bytes: Long): String = "%.1f GB".format(bytes / 1_000_000_000.0)
}
