package ai.localstudio.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityApiKeysBinding
import ai.localstudio.app.databinding.ItemApiKeyBinding
import ai.localstudio.core.keys.ApiKeyEntry
import ai.localstudio.openai.ApiKeyValidationResult
import ai.localstudio.openai.ApiKeyValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Add, delete, and test the keys in one provider's pool.
 *
 * There is deliberately no "edit" — a key is either the right one or it
 * isn't, and typos are easier to fix by deleting and re-adding than by
 * hand-editing a masked password field. Rotation itself (which key is tried
 * first, skipping ones on a 24h cooldown after a quota error) happens inside
 * [ai.localstudio.openai.OpenAiRuntime] using the same [ApiKeyRotator] this
 * screen edits — nothing here talks to the network except the "Проверить"
 * button.
 */
class ApiKeysActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiKeysBinding
    private lateinit var container: AppContainer
    private lateinit var providerId: String
    private val adapter = ApiKeyAdapter(::onValidate, ::onDelete)

    // Validation is not persisted — it only answers "does this key work right
    // now", which can change the moment a quota resets. Keyed by entry id so
    // a delete/add elsewhere in the list does not smear one row's result onto
    // another.
    private val validation = mutableMapOf<String, ValidationState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityApiKeysBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID) ?: CloudProviders.DEMO.id
        title = getString(CloudProviders.byId(providerId).titleRes)

        container = AppContainer.get(this)
        binding.apiKeys.layoutManager = LinearLayoutManager(this)
        binding.apiKeys.adapter = adapter

        binding.addKeyButton.setOnClickListener {
            val key = binding.newKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) return@setOnClickListener
            container.apiKeyRotator(providerId).add(key)
            binding.newKeyInput.text?.clear()
            render()
        }

        render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun render() {
        val entries = container.apiKeyRotator(providerId).pool()
        binding.apiKeysEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.apiKeys.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(entries, validation)
    }

    private fun onDelete(entry: ApiKeyEntry) {
        container.apiKeyRotator(providerId).remove(entry.id)
        validation.remove(entry.id)
        render()
    }

    private fun onValidate(entry: ApiKeyEntry) {
        validation[entry.id] = ValidationState.Checking
        render()
        lifecycleScope.launch {
            val provider = CloudProviders.byId(providerId)
            val result = withContext(Dispatchers.IO) {
                ApiKeyValidator.validate(provider.baseUrl, entry.key)
            }
            validation[entry.id] = ValidationState.Done(result)
            render()
        }
    }

    sealed interface ValidationState {
        data object Checking : ValidationState
        data class Done(val result: ApiKeyValidationResult) : ValidationState
    }

    private class ApiKeyAdapter(
        private val onValidate: (ApiKeyEntry) -> Unit,
        private val onDelete: (ApiKeyEntry) -> Unit,
    ) : RecyclerView.Adapter<ApiKeyAdapter.Holder>() {

        private var entries: List<ApiKeyEntry> = emptyList()
        private var validation: Map<String, ValidationState> = emptyMap()

        fun submit(next: List<ApiKeyEntry>, nextValidation: Map<String, ValidationState>) {
            entries = next
            validation = nextValidation.toMap()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemApiKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(entries[position], validation[entries[position].id], onValidate, onDelete)
        }

        class Holder(private val binding: ItemApiKeyBinding) : RecyclerView.ViewHolder(binding.root) {
            private val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

            fun bind(
                entry: ApiKeyEntry,
                state: ValidationState?,
                onValidate: (ApiKeyEntry) -> Unit,
                onDelete: (ApiKeyEntry) -> Unit,
            ) {
                val context = binding.root.context
                binding.keyTitle.text = mask(entry.key)
                binding.keyStatus.text = statusText(context, entry, state)
                binding.keyValidateButton.isEnabled = state !is ValidationState.Checking
                binding.keyValidateButton.setOnClickListener { onValidate(entry) }
                binding.keyDeleteButton.setOnClickListener { onDelete(entry) }
            }

            private fun statusText(context: Context, entry: ApiKeyEntry, state: ValidationState?): String {
                val cooldown = entry.cooldownUntilEpochMs
                val cooldownText = if (cooldown > System.currentTimeMillis()) {
                    context.getString(R.string.api_keys_cooldown, format.format(Date(cooldown)))
                } else {
                    null
                }
                val validationText = when (state) {
                    null -> context.getString(R.string.api_keys_not_checked)
                    ValidationState.Checking -> context.getString(R.string.api_keys_checking)
                    is ValidationState.Done -> when (state.result) {
                        is ApiKeyValidationResult.Valid -> context.getString(R.string.api_keys_valid)
                        is ApiKeyValidationResult.Invalid -> context.getString(R.string.api_keys_invalid)
                        is ApiKeyValidationResult.RateLimited -> context.getString(R.string.api_keys_rate_limited)
                        is ApiKeyValidationResult.Unknown -> context.getString(R.string.api_keys_unknown)
                    }
                }
                return if (cooldownText != null) "$validationText · $cooldownText" else validationText
            }

            private fun mask(key: String): String = when {
                key.length <= 8 -> "•".repeat(key.length)
                else -> key.take(4) + "…" + key.takeLast(4)
            }
        }
    }

    companion object {
        const val EXTRA_PROVIDER_ID = "providerId"

        fun intent(context: Context, providerId: String) =
            Intent(context, ApiKeysActivity::class.java).putExtra(EXTRA_PROVIDER_ID, providerId)
    }
}
