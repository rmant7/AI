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
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
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

    // Persisted the moment a checkbox changes (see buildProviderCheckboxes'
    // own listener), but also kept in memory here so checking several
    // providers in one visit (Gemini, then Mistral) accumulates correctly.
    private var pendingEnabled = mutableSetOf<String>()

    /** One row per [CloudProviders.ALL] entry — see [buildProviderCheckboxes]. */
    private val providerCheckboxes = mutableMapOf<String, CheckBox>()

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
        buildProviderCheckboxes()

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
        showProvider(settings.provider)
        renderEnabledSummary()

        binding.generationSettingsButton.setOnClickListener {
            startActivity(Intent(this, GenerationSettingsActivity::class.java))
        }
        binding.apiKeysButton.setOnClickListener {
            startActivity(ApiKeysActivity.intent(this, settings.providerId))
        }
        setupDownloadPolicy()

        // AICore's status can change while this screen is open (re-enabled
        // in system settings, re-checked on resume) — Gemini Nano's row
        // must follow it rather than stay greyed out until the next visit.
        lifecycleScope.launch {
            container.aicoreStatus
                .map { container.aicoreUnsupported }
                .distinctUntilChanged()
                .drop(1)
                .collect { buildProviderCheckboxes() }
        }
    }

    override fun onResume() {
        super.onResume()
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

    /**
     * One [CheckBox] per [CloudProviders.ALL] entry, replacing the old
     * spinner + separate "enable" checkbox — real user feedback: seeing
     * every provider's on/off state took spinning through each one in turn,
     * with nothing showing which were already enabled without doing that.
     * Checking a row both toggles [pendingEnabled] and selects that provider
     * for the detail panel below ([showProvider]) — unchecking it again
     * still leaves it selected, so its endpoint/key/model stay visible and
     * editable while temporarily disabled.
     */
    private fun buildProviderCheckboxes() {
        binding.providerCheckboxList.removeAllViews()
        providerCheckboxes.clear()
        for (provider in CloudProviders.ALL) {
            // Gemini Nano on a device where AICore itself said it can't run:
            // shown, but not selectable — see AppContainer.aicoreUnsupported.
            val unavailable = provider.id == CloudProviders.AICORE.id && container.aicoreUnsupported
            val box = CheckBox(this).apply {
                text = if (unavailable) {
                    getString(provider.titleRes) + " — " + getString(R.string.models_aicore_unavailable)
                } else {
                    getString(provider.titleRes)
                }
                isEnabled = !unavailable
                isChecked = provider.id in pendingEnabled && !unavailable
                setOnCheckedChangeListener { _, checked ->
                    if (checked) pendingEnabled += provider.id else pendingEnabled -= provider.id
                    settings.enabledProviderIds = pendingEnabled
                    settings.providerId = provider.id
                    showProvider(provider)
                    renderEnabledSummary()
                }
            }
            binding.providerCheckboxList.addView(box)
            providerCheckboxes[provider.id] = box
        }
    }

    private fun setupDownloadPolicy() {
        val checkedId = when (settings.downloadPolicy) {
            Settings.DownloadPolicy.WIFI_ONLY -> R.id.downloadPolicyWifiOnly
            Settings.DownloadPolicy.WIFI_AND_MOBILE -> R.id.downloadPolicyWifiAndMobile
            Settings.DownloadPolicy.ASK_EVERY_TIME -> R.id.downloadPolicyAskEveryTime
        }
        binding.downloadPolicyGroup.check(checkedId)
        binding.downloadPolicyGroup.setOnCheckedChangeListener { _, id ->
            settings.downloadPolicy = when (id) {
                R.id.downloadPolicyWifiAndMobile -> Settings.DownloadPolicy.WIFI_AND_MOBILE
                R.id.downloadPolicyAskEveryTime -> Settings.DownloadPolicy.ASK_EVERY_TIME
                else -> Settings.DownloadPolicy.WIFI_ONLY
            }
        }

        binding.mapWeightsCheck.isChecked = settings.mapModelWeights
        binding.mapWeightsCheck.setOnCheckedChangeListener { _, checked -> settings.mapModelWeights = checked }
        binding.autoDownloadCheck.isChecked = settings.autoDownloadEnabled
        binding.autoDownloadCheck.setOnCheckedChangeListener { _, checked -> settings.autoDownloadEnabled = checked }
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
        // Bold marks which row's detail panel is showing below — distinct
        // from the checkbox's own checked state, which only means enabled.
        providerCheckboxes.forEach { (id, box) -> box.setTypeface(null, if (id == provider.id) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) }
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
}
