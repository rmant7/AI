package ai.localstudio.app

import ai.localstudio.app.aicore.AiCorePromptClient
import ai.localstudio.app.databinding.ActivityAiCoreTestBinding
import ai.localstudio.core.util.describeForUser
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    private var downloadJob: Job? = null
    private var testJob: Job? = null

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
        if (downloadJob != null) {
            // Real device report: this ran for several real minutes with no
            // visible progress at all — the first thing that needed fixing
            // was not silence-vs-noise, it was having no way to stop it.
            downloadJob?.cancel()
            return
        }
        setBusy(true)
        binding.aiCoreDownloadButton.isEnabled = true
        binding.aiCoreDownloadButton.text = getString(R.string.aicore_cancel_download)
        binding.aiCoreProgress.visibility = View.VISIBLE
        binding.aiCoreProgress.isIndeterminate = true
        downloadJob = lifecycleScope.launch {
            var totalBytes = 0L
            val outcome = runCatching {
                client.ensureDownloaded { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> totalBytes = status.bytesToDownload
                        is DownloadStatus.DownloadProgress -> {
                            binding.aiCoreProgress.isIndeterminate = totalBytes <= 0
                            if (totalBytes > 0) {
                                binding.aiCoreProgress.progress = (status.totalBytesDownloaded * 100 / totalBytes).toInt()
                            }
                            binding.aiCoreStatus.text = getString(
                                R.string.aicore_download_progress,
                                mb(status.totalBytesDownloaded),
                                if (totalBytes > 0) mb(totalBytes) else "?",
                            )
                        }
                        else -> Unit
                    }
                }
            }
            binding.aiCoreProgress.visibility = View.GONE
            binding.aiCoreDownloadButton.text = getString(R.string.aicore_download)
            outcome.fold(
                onSuccess = {
                    binding.aiCoreStatus.text = getString(R.string.aicore_status_line, getString(R.string.aicore_status_available))
                    binding.aiCoreDownloadButton.visibility = View.GONE
                },
                onFailure = { error ->
                    if (error is CancellationException) {
                        binding.aiCoreStatus.text = getString(R.string.aicore_download_cancelled)
                    } else {
                        binding.aiCoreStatus.text = getString(R.string.aicore_status_error, error.describeForUser())
                    }
                },
            )
            downloadJob = null
            setBusy(false)
        }
    }

    private fun mb(bytes: Long): String = "%.0f MB".format(bytes / 1_000_000.0)

    private fun runTest() {
        if (testJob != null) {
            // Real device report: after Check status reported "available",
            // tapping Test produced no visible feedback at all — same class
            // of bug as the download flow's silent multi-minute wait. The
            // Prompt API exposes no intermediate progress for generateContent
            // (unlike download()'s Flow<DownloadStatus>), so the best this
            // screen can offer is: make it obvious a request is in flight,
            // and let the user bail out instead of wondering if it's stuck.
            testJob?.cancel()
            return
        }
        if (busy) return
        val prompt = binding.aiCorePromptInput.text?.toString().orEmpty().trim()
        if (prompt.isBlank()) return
        setBusy(true)
        binding.aiCoreTestButton.isEnabled = true
        binding.aiCoreTestButton.text = getString(R.string.aicore_cancel_test)
        binding.aiCoreProgress.visibility = View.VISIBLE
        binding.aiCoreProgress.isIndeterminate = true
        binding.aiCoreResult.visibility = View.VISIBLE
        binding.aiCoreResult.text = getString(R.string.aicore_testing)
        testJob = lifecycleScope.launch {
            val outcome = runCatching { client.generate(prompt) }
            binding.aiCoreProgress.visibility = View.GONE
            binding.aiCoreTestButton.text = getString(R.string.aicore_test)
            binding.aiCoreResult.text = outcome.fold(
                onSuccess = { it },
                onFailure = { error ->
                    if (error is CancellationException) {
                        getString(R.string.aicore_test_cancelled)
                    } else {
                        getString(R.string.aicore_result_error, error.describeForUser())
                    }
                },
            )
            testJob = null
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
