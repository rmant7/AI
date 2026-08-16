package ai.localstudio.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.localstudio.app.databinding.ActivitySettingsBinding

/**
 * Picking a provider is the whole configuration: presets carry their own
 * address, so for a cloud model the user supplies an API key and nothing else.
 * Key and model name are stored per provider, so switching back and forth does
 * not send one provider's key to another.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = AppContainer.get(this).settings

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
        showProvider(settings.provider)

        binding.saveButton.setOnClickListener { save() }
    }

    private fun showProvider(provider: CloudProvider) {
        binding.providerHint.text = provider.keyHint
        binding.endpointBlock.visibility = if (provider.editableUrl) View.VISIBLE else View.GONE
        binding.apiKeyBlock.visibility = if (provider.needsKey) View.VISIBLE else View.GONE

        binding.endpointInput.setText(settings.customEndpoint.ifBlank { provider.baseUrl })
        binding.apiKeyInput.setText(settings.apiKey)
        binding.chatModelInput.setText(settings.chatModel)
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

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
