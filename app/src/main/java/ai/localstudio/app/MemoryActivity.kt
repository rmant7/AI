package ai.localstudio.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivityMemoryBinding
import ai.localstudio.app.llama.ExperimentalDownloadState
import ai.localstudio.app.llama.ExperimentalEmbeddingModels
import kotlinx.coroutines.launch

/**
 * Memory as a feature someone turns on or off, not a model they configure —
 * see this task's own UI/architecture audit for why the embedding model
 * used to sit inside the Text tab of Models and why that read as "another
 * model to pick" rather than "how memory works". This screen owns exactly
 * the two switches that decide whether memory does anything at all
 * ([Settings.memoryEnabled]) and whether its semantic half is allowed to
 * ([Settings.semanticMemoryEnabled]); which embedding model backs the
 * semantic half is [ModelsActivity]'s own concern (the "Manage" button
 * below deep-links there), the same separation Models already keeps
 * between choosing a chat model and configuring generation parameters.
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemoryBinding
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)

        binding.memoryEnabledCheck.isChecked = container.settings.memoryEnabled
        binding.memoryEnabledCheck.setOnCheckedChangeListener { _, checked ->
            container.settings.memoryEnabled = checked
        }

        binding.memorySemanticCheck.isChecked = container.settings.semanticMemoryEnabled
        binding.memorySemanticCheck.setOnCheckedChangeListener { _, checked ->
            container.settings.semanticMemoryEnabled = checked
            renderModelStatus()
        }

        binding.memoryManageModelButton.setOnClickListener {
            startActivity(ModelsActivity.intent(this, ModelsActivity.Category.EMBEDDING))
        }

        lifecycleScope.launch { container.experimentalEmbeddingDownloads.state.collect { renderModelStatus() } }
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
        binding.memoryEnabledCheck.isChecked = container.settings.memoryEnabled
        binding.memorySemanticCheck.isChecked = container.settings.semanticMemoryEnabled
        renderModelStatus()
        renderIndexStatus()
    }

    private fun renderModelStatus() {
        val spec = ExperimentalEmbeddingModels.E5_BASE
        val installed = container.experimentalEmbeddingDownloads.stateOf(spec) is ExperimentalDownloadState.Installed
        binding.memoryModelStatus.text = when {
            !installed -> getString(R.string.memory_model_not_downloaded, spec.title)
            !container.settings.semanticMemoryEnabled -> getString(R.string.memory_model_disabled, spec.title)
            container.semanticEmbedderReady -> getString(R.string.memory_model_ready, spec.title)
            else -> getString(R.string.memory_model_loading, spec.title)
        }
    }

    /**
     * [AppContainer.semanticIndexStatus] walks every memory item plus the
     * on-disk vector index, which is cheap but not free — worth its own
     * coroutine rather than blocking [render] on the main thread.
     */
    private fun renderIndexStatus() {
        lifecycleScope.launch {
            val (embedded, total) = container.semanticIndexStatus()
            binding.memoryIndexStatus.text = getString(R.string.memory_index_status, embedded, total)
        }
    }
}
