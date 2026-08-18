package ai.localstudio.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivitySettingsBinding
import ai.localstudio.app.whisper.WhisperDownloadState
import ai.localstudio.app.whisper.WhisperModels
import kotlinx.coroutines.launch

/**
 * Picking a provider is the whole configuration: presets carry their own
 * address, so for a cloud model the user supplies an API key and nothing else.
 * Key and model name are stored per provider, so switching back and forth does
 * not send one provider's key to another.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings
    private lateinit var container: AppContainer

    // Deferred to Save like every other field on this screen, but tracked
    // in memory across spinner switches so enabling several providers in
    // one visit (switch to Gemini, check it, switch to Mistral, check that
    // too) accumulates correctly instead of only ever remembering one.
    private var pendingEnabled = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)
        settings = container.settings
        pendingEnabled = settings.enabledProviderIds.toMutableSet()

        binding.providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            CloudProviders.ALL.map { it.title },
        )
        binding.providerSpinner.setSelection(
            CloudProviders.ALL.indexOfFirst { it.id == settings.providerId }.coerceAtLeast(0),
        )
        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Persisted immediately so the per-provider key and model below
                // are read and written under the right provider.
                settings.providerId = CloudProviders.ALL[position].id
                showProvider(CloudProviders.ALL[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.asrModelInput.setText(settings.speechModel)
        binding.systemPromptInput.setText(settings.systemPrompt)
        binding.ramInput.setText(settings.ramBudgetPercent.toString())
        binding.hfTokenInput.setText(settings.huggingFaceToken)
        binding.providerEnabledCheck.setOnCheckedChangeListener { _, checked ->
            val id = settings.providerId
            if (checked) pendingEnabled += id else pendingEnabled -= id
            renderEnabledSummary()
        }
        showProvider(settings.provider)
        renderEnabledSummary()

        binding.saveButton.setOnClickListener { save() }
        binding.generationSettingsButton.setOnClickListener {
            startActivity(Intent(this, GenerationSettingsActivity::class.java))
        }
        // Voice models live in the Models screen next to the chat models now,
        // not in a corner of Settings — this only points there.
        binding.whisperManageButton.setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }

        lifecycleScope.launch { container.whisperDownloads.state.collect { renderWhisper() } }
        renderWhisper()
    }

    override fun onResume() {
        super.onResume()
        renderWhisper()
    }

    private fun renderWhisper() {
        val installed = container.whisperStore.installedSeed(settings.whisperModelId)
        val running = WhisperModels.SEEDS.firstOrNull { container.whisperDownloads.stateOf(it) is WhisperDownloadState.Running }
        val failed = WhisperModels.SEEDS.firstOrNull { container.whisperDownloads.stateOf(it) is WhisperDownloadState.Failed }

        binding.whisperStatus.text = when {
            running != null -> {
                val state = container.whisperDownloads.stateOf(running) as WhisperDownloadState.Running
                "${running.title}: ${state.stage} ${(state.progress.fraction * 100).toInt()}%"
            }

            failed != null -> "Ошибка: " + (container.whisperDownloads.stateOf(failed) as WhisperDownloadState.Failed).message
            installed != null -> getString(R.string.settings_whisper_installed, installed.title)
            else -> getString(R.string.settings_whisper_none)
        }
    }

    private fun showProvider(provider: CloudProvider) {
        binding.providerHint.text = provider.keyHint
        binding.endpointBlock.visibility = if (provider.editableUrl) View.VISIBLE else View.GONE
        binding.apiKeyBlock.visibility = if (provider.needsKey) View.VISIBLE else View.GONE

        binding.endpointInput.setText(settings.customEndpoint.ifBlank { provider.baseUrl })
        binding.apiKeyInput.setText(settings.apiKey)
        binding.chatModelInput.setText(settings.chatModel)

        binding.chatModelInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, provider.freeModels),
        )
        binding.chatModelInput.setOnClickListener { binding.chatModelInput.showDropDown() }
        binding.chatModelHint.text = if (provider.freeModels.isEmpty()) {
            ""
        } else {
            getString(R.string.settings_chat_model_hint)
        }
        binding.chatModelHint.visibility = if (provider.freeModels.isEmpty()) View.GONE else View.VISIBLE
        // Setting this can re-fire the checkbox's own listener, but that
        // listener only reproduces the membership state already being set
        // here, so it's a harmless no-op rather than something to suppress.
        binding.providerEnabledCheck.isChecked = provider.id in pendingEnabled
    }

    private fun renderEnabledSummary() {
        val titles = CloudProviders.ALL.filter { it.id in pendingEnabled }.map { it.title }
        binding.enabledProvidersSummary.text = if (titles.isEmpty()) {
            getString(R.string.settings_no_sources)
        } else {
            getString(R.string.settings_active_sources, titles.joinToString(" → "))
        }
    }

    private fun save() {
        val provider = settings.provider
        if (provider.editableUrl) {
            settings.customEndpoint = binding.endpointInput.text?.toString().orEmpty()
        }
        if (provider.needsKey) {
            settings.apiKey = binding.apiKeyInput.text?.toString().orEmpty()
        }
        settings.chatModel = binding.chatModelInput.text?.toString().orEmpty()
        settings.speechModel = binding.asrModelInput.text?.toString().orEmpty()
        settings.systemPrompt = binding.systemPromptInput.text?.toString().orEmpty()
        binding.ramInput.text?.toString()?.trim()?.toIntOrNull()?.let { settings.ramBudgetPercent = it }
        settings.huggingFaceToken = binding.hfTokenInput.text?.toString().orEmpty()
        settings.enabledProviderIds = pendingEnabled

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ABOUT, 0, R.string.settings_about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ABOUT -> {
            showAbout()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** Exactly "is this the build I was just sent" — the git commit an APK was built from, not a version number nobody bumps. */
    private fun showAbout() {
        val message = getString(
            R.string.settings_about_body,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_SHA,
            BuildConfig.CI_RUN,
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_about)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val MENU_ABOUT = 1
    }
}
