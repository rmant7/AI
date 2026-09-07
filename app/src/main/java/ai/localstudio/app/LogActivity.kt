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

/**
 * Read-only view of [ai.localstudio.app.log.AppLog] — the whole point is
 * getting a report of what went wrong OFF the phone: one tap copies
 * everything to the clipboard so it can be pasted wherever it needs to go,
 * without anyone having to plug the phone into a computer and pull logcat.
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
        val log = container.appLog.readAll()
        binding.logText.text = log.ifBlank { getString(R.string.log_empty) }
    }

    private fun copyLog() {
        val log = container.appLog.readAll()
        if (log.isBlank()) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Local AI Studio log", log))
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
}
