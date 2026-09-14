package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivityExperimentalEmbeddingsBinding
import ai.localstudio.app.databinding.ItemExperimentalEmbeddingBinding
import ai.localstudio.app.llama.EmbeddingModelSpec
import ai.localstudio.app.llama.ExperimentalDownloadState
import ai.localstudio.app.llama.ExperimentalEmbeddingModels
import ai.localstudio.app.llama.ExperimentalEmbeddingTester
import ai.localstudio.app.llama.LlamaBridge
import kotlinx.coroutines.launch

/**
 * Lets a candidate [ai.localstudio.memory.MemoryEmbedder] model (see
 * [ExperimentalEmbeddingModels]) be downloaded and sanity-checked entirely
 * from inside the app — no `adb push`, no `./gradlew connectedDebugAndroidTest`,
 * both of which need a computer plugged into the phone. On a phone-only
 * workflow this screen (download → Test) is now the whole verification loop
 * [ai.localstudio.app.llama.ExperimentalEmbeddingModelTest]'s doc comment
 * describes, just reachable without a cable.
 *
 * [ExperimentalEmbeddingModels.E5_BASE] itself graduated out of "only
 * reachable here" this way: [AppContainer] now downloads and loads it
 * automatically, so this screen mostly matters for *future* candidates —
 * re-running its Test button on Base still works (useful after a model
 * update), it just no longer gates whether Base is used in real memory
 * retrieval the way it used to.
 *
 * Deliberately not reachable from anywhere a normal user would find it by
 * accident beyond the one entry point, under Settings → Advanced (moved
 * there from [ModelsActivity], which now only ever shows
 * [ExperimentalEmbeddingModels.E5_BASE] under its own Embedding tab) —
 * nothing this screen does ever touches
 * [ai.localstudio.app.models.LocalModels] or switches the app's actual
 * chat model. It exists purely so the person running this manual
 * verification can get a candidate model's own numbers (dimension, cosine
 * scores) before anyone decides whether it belongs in production.
 */
class ExperimentalEmbeddingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExperimentalEmbeddingsBinding
    private lateinit var container: AppContainer
    private val rows = mutableMapOf<String, ItemExperimentalEmbeddingBinding>()
    private var testing: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExperimentalEmbeddingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.embed_screen_title)

        container = AppContainer.get(this)

        ExperimentalEmbeddingModels.ALL.forEach { spec ->
            val itemBinding = ItemExperimentalEmbeddingBinding.inflate(LayoutInflater.from(this), binding.embedList, true)
            rows[spec.id] = itemBinding
        }

        lifecycleScope.launch { container.experimentalEmbeddingDownloads.state.collect { render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        ExperimentalEmbeddingModels.ALL.forEach { spec ->
            val binding = rows[spec.id] ?: return@forEach
            val state = container.experimentalEmbeddingDownloads.stateOf(spec)
            bind(binding, spec, state)
        }
    }

    private fun bind(binding: ItemExperimentalEmbeddingBinding, spec: EmbeddingModelSpec, state: ExperimentalDownloadState) {
        binding.embedTitle.text = spec.title
        binding.embedSubtitle.text = "${spec.repoId} · ${spec.quantLabel} · ${spec.dimension}d"

        val status = statusText(state, spec)
        binding.embedStatus.text = status.orEmpty()
        binding.embedStatus.visibility = if (status.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE

        val running = state is ExperimentalDownloadState.Running || state is ExperimentalDownloadState.Resolving
        binding.embedProgress.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.embedProgress.isIndeterminate = state is ExperimentalDownloadState.Resolving
        (state as? ExperimentalDownloadState.Running)?.let { binding.embedProgress.progress = (it.progress.fraction * 100).toInt() }

        binding.embedPrimaryButton.text = when (state) {
            is ExperimentalDownloadState.Installed -> getString(R.string.model_installed)
            is ExperimentalDownloadState.Running, is ExperimentalDownloadState.Resolving -> getString(R.string.model_cancel)
            is ExperimentalDownloadState.Failed -> getString(R.string.model_retry)
            ExperimentalDownloadState.Idle -> getString(R.string.model_download)
        }
        binding.embedPrimaryButton.isEnabled = state !is ExperimentalDownloadState.Installed
        binding.embedPrimaryButton.setOnClickListener {
            when (state) {
                is ExperimentalDownloadState.Running, is ExperimentalDownloadState.Resolving ->
                    container.experimentalEmbeddingDownloads.cancel(spec)
                else -> NetworkPolicy.confirmIfNeeded(this, container.settings) { container.experimentalEmbeddingDownloads.start(spec) }
            }
        }

        binding.embedSecondaryButton.visibility = if (state is ExperimentalDownloadState.Installed) android.view.View.VISIBLE else android.view.View.GONE
        binding.embedSecondaryButton.setOnClickListener {
            container.experimentalEmbeddingDownloads.delete(spec)
            binding.embedResult.visibility = android.view.View.GONE
        }

        val canTest = state is ExperimentalDownloadState.Installed && testing != spec.id
        binding.embedTestButton.visibility = if (state is ExperimentalDownloadState.Installed) android.view.View.VISIBLE else android.view.View.GONE
        binding.embedTestButton.isEnabled = canTest
        binding.embedTestButton.text = if (testing == spec.id) getString(R.string.embed_testing) else getString(R.string.embed_test)
        binding.embedTestButton.setOnClickListener { runTest(binding, spec) }
    }

    private fun statusText(state: ExperimentalDownloadState, spec: EmbeddingModelSpec): String? = when (state) {
        is ExperimentalDownloadState.Installed ->
            getString(R.string.model_state_installed) + " · ${size(container.experimentalEmbeddingStore.installedSize(spec))}"
        ExperimentalDownloadState.Resolving -> getString(R.string.model_state_resolving, spec.repoId)
        is ExperimentalDownloadState.Running ->
            getString(
                R.string.download_progress_label,
                spec.repoId,
                size(state.progress.bytesDownloaded),
                if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?",
            )
        is ExperimentalDownloadState.Failed -> getString(R.string.model_state_error, state.message.lineSequence().first())
        ExperimentalDownloadState.Idle -> null
    }

    private fun runTest(binding: ItemExperimentalEmbeddingBinding, spec: EmbeddingModelSpec) {
        if (testing != null) return
        if (!LlamaBridge.isAvailable) {
            Toast.makeText(this, R.string.model_native_missing, Toast.LENGTH_SHORT).show()
            return
        }
        testing = spec.id
        render()
        binding.embedResult.visibility = android.view.View.VISIBLE
        binding.embedResult.text = getString(R.string.embed_testing)
        lifecycleScope.launch {
            val outcome = runCatching {
                ExperimentalEmbeddingTester.run(LlamaBridge(), container.experimentalEmbeddingStore.fileFor(spec), spec)
            }
            binding.embedResult.text = outcome.fold(
                onSuccess = { result ->
                    getString(
                        R.string.embed_result,
                        result.dimension,
                        result.queryVectorL2Norm,
                        result.cosineSimilar,
                        result.cosineDissimilar,
                        if (result.looksSensible) getString(R.string.embed_result_ok) else getString(R.string.embed_result_suspicious),
                    )
                },
                onFailure = { error -> getString(R.string.embed_result_error, error.message ?: error.toString()) },
            )
            testing = null
            render()
        }
    }

    private fun size(bytes: Long): String =
        if (bytes >= 1_000_000_000) getString(R.string.unit_gb, "%.2f".format(bytes / 1_000_000_000.0))
        else getString(R.string.unit_mb, "%.0f".format(bytes / 1_000_000.0))
}
