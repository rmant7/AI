package ai.localstudio.app

import ai.localstudio.app.aicore.AiCorePromptClient
import ai.localstudio.app.databinding.ActivityAiCoreTestBinding
import ai.localstudio.core.util.describeForUser
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch

/**
 * Manual, phone-only smoke test for Gemini Nano via AICore (ML Kit's Prompt
 * API) — see docs/04-runtime.md's "Gemini Nano / AICore feasibility"
 * section this screen exists to actually answer, not just speculate about.
 * Two things could not be verified from a sandboxed dev environment with no
 * device and no access to Google's own docs (network policy blocks both):
 * whether the Prompt API is reachable at all on a given build (it shipped
 * alpha, is beta as of this screen's own writing, and could still be gated
 * further in ways only a real call reveals), and — the one that actually
 * decides whether this is worth building on for real — whether Gemini
 * Nano's own answer to a Russian prompt is any good, since Russian is one
 * of this app's two primary languages.
 *
 * Same "download → Test" shape as [ExperimentalEmbeddingsActivity], and the
 * same placement: reachable only from Settings → Advanced, never from
 * anywhere a normal user would land on by accident — nothing here changes
 * which model the app actually uses for anything.
 */
class AiCoreTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiCoreTestBinding
    private val client = AiCorePromptClient()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiCoreTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.aicore_screen_title)

        binding.aiCoreCheckButton.setOnClickListener { checkStatus() }
        binding.aiCoreDownloadButton.setOnClickListener { download() }
        binding.aiCoreTestButton.setOnClickListener { runTest() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Off the main thread: AiCorePromptClient.close() may block on an
        // IPC round-trip to the AICore system service, same reasoning every
        // other engine's release() in this app avoids calling straight from
        // onDestroy for.
        val toClose = client
        Thread { toClose.close() }.start()
    }

    private fun checkStatus() {
        if (busy) return
        setBusy(true)
        binding.aiCoreDownloadButton.visibility = View.GONE
        lifecycleScope.launch {
            val outcome = runCatching { client.status() }
            outcome.fold(
                onSuccess = { status ->
                    binding.aiCoreStatus.text = getString(R.string.aicore_status_line, describeStatus(status))
                    binding.aiCoreDownloadButton.visibility = if (status == FeatureStatus.DOWNLOADABLE) View.VISIBLE else View.GONE
                },
                onFailure = { error ->
                    binding.aiCoreStatus.text = getString(R.string.aicore_status_error, error.describeForUser())
                },
            )
            setBusy(false)
        }
    }

    private fun describeStatus(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> getString(R.string.aicore_status_available)
        FeatureStatus.DOWNLOADABLE -> getString(R.string.aicore_status_downloadable)
        FeatureStatus.UNAVAILABLE -> getString(R.string.aicore_status_unavailable)
        // Whatever future FeatureStatus values this SDK version adds — see
        // AiCorePromptClient.status()'s own doc comment for why this isn't
        // treated as exhaustive.
        else -> status.toString()
    }

    private fun download() {
        if (busy) return
        setBusy(true)
        binding.aiCoreProgress.visibility = View.VISIBLE
        binding.aiCoreProgress.isIndeterminate = true
        lifecycleScope.launch {
            // No progress readout — see AiCorePromptClient.ensureDownloaded()'s
            // own doc comment for why: GenerativeModel.download()'s
            // DownloadStatus field names aren't verified against anything
            // beyond a search snippet, unlike the calls this screen actually
            // makes, so this only reports done-or-failed rather than guessing
            // at fields a bad guess would silently misreport.
            val outcome = runCatching { client.ensureDownloaded() }
            binding.aiCoreProgress.visibility = View.GONE
            outcome.fold(
                onSuccess = {
                    binding.aiCoreStatus.text = getString(R.string.aicore_status_line, getString(R.string.aicore_status_available))
                    binding.aiCoreDownloadButton.visibility = View.GONE
                },
                onFailure = { error ->
                    binding.aiCoreStatus.text = getString(R.string.aicore_status_error, error.describeForUser())
                },
            )
            setBusy(false)
        }
    }

    private fun runTest() {
        if (busy) return
        val prompt = binding.aiCorePromptInput.text?.toString().orEmpty().trim()
        if (prompt.isBlank()) return
        setBusy(true)
        binding.aiCoreResult.visibility = View.VISIBLE
        binding.aiCoreResult.text = getString(R.string.aicore_testing)
        lifecycleScope.launch {
            val outcome = runCatching { client.generate(prompt) }
            binding.aiCoreResult.text = outcome.fold(
                onSuccess = { it },
                onFailure = { error -> getString(R.string.aicore_result_error, error.describeForUser()) },
            )
            setBusy(false)
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.aiCoreCheckButton.isEnabled = !value
        binding.aiCoreDownloadButton.isEnabled = !value
        binding.aiCoreTestButton.isEnabled = !value
    }
}
