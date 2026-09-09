package ai.localstudio.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivitySettingsBinding
import ai.localstudio.app.llama.LlamaBridge
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

    // Persisted the moment the checkbox changes (see the listener below), but
    // also kept in memory across spinner switches so enabling several
    // providers in one visit (switch to Gemini, check it, switch to Mistral,
    // check that too) accumulates correctly instead of only ever remembering
    // whichever provider is selected right now.
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

        setupLanguageSpinner()

        binding.providerSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            CloudProviders.ALL.map { getString(it.titleRes) },
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

        binding.compareModeCheck.isChecked = settings.compareMode
        binding.compareModeCheck.setOnCheckedChangeListener { _, checked -> settings.compareMode = checked }
        binding.systemPromptInput.setText(settings.systemPrompt)
        binding.systemPromptInput.persistOnChange { settings.systemPrompt = it }
        binding.ramInput.setText(settings.ramBudgetPercent.toString())
        binding.ramInput.persistOnChange { value -> value.trim().toIntOrNull()?.let { settings.ramBudgetPercent = it } }
        binding.hfTokenInput.setText(settings.huggingFaceToken)
        binding.hfTokenInput.persistOnChange { settings.huggingFaceToken = it }
        binding.endpointInput.persistOnChange { value -> if (settings.provider.editableUrl) settings.customEndpoint = value }
        binding.apiKeyInput.persistOnChange { value -> if (settings.provider.needsKey) settings.apiKey = value }
        binding.chatModelInput.persistOnChange { settings.chatModel = it }
        binding.providerEnabledCheck.setOnCheckedChangeListener { _, checked ->
            val id = settings.providerId
            if (checked) pendingEnabled += id else pendingEnabled -= id
            settings.enabledProviderIds = pendingEnabled
            renderEnabledSummary()
        }
        showProvider(settings.provider)
        renderEnabledSummary()

        binding.generationSettingsButton.setOnClickListener {
            startActivity(Intent(this, GenerationSettingsActivity::class.java))
        }
        // Voice models live in the Models screen next to the chat models now,
        // not in a corner of Settings — this only points there.
        binding.whisperManageButton.setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        binding.apiKeysButton.setOnClickListener {
            startActivity(ApiKeysActivity.intent(this, settings.providerId))
        }

        lifecycleScope.launch { container.whisperDownloads.state.collect { renderWhisper() } }
        renderWhisper()
    }

    override fun onResume() {
        super.onResume()
        renderWhisper()
        // Refreshes the count after a visit to ApiKeysActivity — added,
        // deleted, or exhausted keys there should be reflected the moment
        // this screen is visible again, not only after re-selecting the
        // provider from the spinner.
        renderApiKeysSummary(settings.provider)
    }

    /**
     * `null` means "system default" — an empty [LocaleListCompat], which
     * tells AppCompat to stop overriding and follow the device language
     * again. AppCompat persists whichever choice is made here itself, so
     * there's nothing else to store or read back on the next launch.
     */
    private fun setupLanguageSpinner() {
        val tags = listOf(null, "en", "ru")
        binding.languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.settings_language_system),
                getString(R.string.settings_language_en),
                getString(R.string.settings_language_ru),
            ),
        )
        val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',').ifBlank { null }
        binding.languageSpinner.setSelection(tags.indexOf(currentTag).coerceAtLeast(0))
        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val tag = tags[position]
                val newLocales = if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                if (newLocales.toLanguageTags() != AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                    AppCompatDelegate.setApplicationLocales(newLocales)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
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

            failed != null -> getString(
                R.string.settings_whisper_error,
                (container.whisperDownloads.stateOf(failed) as WhisperDownloadState.Failed).message,
            )
            installed != null -> getString(R.string.settings_whisper_installed, installed.title)
            else -> getString(R.string.settings_whisper_none)
        }
    }

    private fun showProvider(provider: CloudProvider) {
        binding.providerHint.text = getString(provider.keyHintRes)
        binding.endpointBlock.visibility = if (provider.editableUrl) View.VISIBLE else View.GONE
        binding.apiKeyBlock.visibility = if (provider.needsKey) View.VISIBLE else View.GONE

        binding.endpointInput.setText(settings.customEndpoint.ifBlank { provider.baseUrl })
        binding.apiKeyInput.setText(settings.apiKey)
        binding.chatModelInput.setText(settings.chatModel)
        renderApiKeysSummary(provider)

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

    private fun renderApiKeysSummary(provider: CloudProvider) {
        if (!provider.needsKey) return
        val pool = container.apiKeyRotator(provider.id).pool()
        val onCooldown = pool.count { it.cooldownUntilEpochMs > System.currentTimeMillis() }
        binding.apiKeysSummary.text = when {
            pool.isEmpty() -> getString(R.string.api_keys_summary_none)
            onCooldown == 0 -> getString(R.string.api_keys_summary_ready, pool.size)
            else -> getString(R.string.api_keys_summary_partial, pool.size, onCooldown)
        }
    }

    private fun renderEnabledSummary() {
        val titles = CloudProviders.ALL.filter { it.id in pendingEnabled }.map { getString(it.titleRes) }
        binding.enabledProvidersSummary.text = if (titles.isEmpty()) {
            getString(R.string.settings_no_sources)
        } else {
            getString(R.string.settings_active_sources, titles.joinToString(" → "))
        }
    }

    /** Every field on this screen persists as it's typed — nothing here waits for an explicit Save. */
    private fun EditText.persistOnChange(set: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = set(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ABOUT, 0, R.string.settings_about)
        menu.add(0, MENU_LOG, 1, R.string.settings_log)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_ABOUT -> {
            showAbout()
            true
        }

        MENU_LOG -> {
            startActivity(Intent(this, LogActivity::class.java))
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** Exactly "is this the build I was just sent" — the git commit an APK was built from, not a version number nobody bumps. */
    private fun showAbout() {
        val base = getString(
            R.string.settings_about_body,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.GIT_SHA,
            BuildConfig.CI_RUN,
        )
        // llama_print_system_info() just formats compile-time flags — no
        // model load involved — so this is cheap even including the native
        // library's first System.loadLibrary() call, and worth having up
        // front: whether dotprod/i8mm/fp16 were actually detected for this
        // device's CPU is exactly what settles "is this slow because of the
        // build, or because the hardware itself can't go faster".
        val cpuInfo = if (LlamaBridge.isAvailable) {
            runCatching { LlamaBridge().nativeSystemInfo() }.getOrNull()
        } else {
            null
        }
        val cpuLine = "\n\n" + if (cpuInfo.isNullOrBlank()) {
            getString(R.string.settings_about_cpu_unavailable)
        } else {
            getString(R.string.settings_about_cpu, cpuInfo)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_about)
            .setMessage(base + cpuLine)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val MENU_ABOUT = 1
        const val MENU_LOG = 2
    }
}
