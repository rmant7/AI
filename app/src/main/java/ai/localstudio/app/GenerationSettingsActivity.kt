package ai.localstudio.app

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.localstudio.app.databinding.ActivityGenerationSettingsBinding
import ai.localstudio.app.databinding.ItemGenerationParamBinding

/**
 * Sampling parameters, one explained card each.
 *
 * These were previously five unlabelled number fields wedged between the
 * provider settings and the RAM budget — a wall of numbers with no indication
 * of what any of them do, what values are legal, or which way to move one when
 * answers come out wrong. Each now states its range, its default, what it
 * actually changes, and which direction to nudge it for a given symptom.
 */
class GenerationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGenerationSettingsBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGenerationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = AppContainer.get(this).settings

        bindFields()
        binding.saveButton.setOnClickListener { save() }
        binding.resetButton.setOnClickListener { reset() }
    }

    private fun bindFields() {
        binding.temperature.fill(
            R.string.settings_temperature,
            R.string.settings_temperature_help,
            settings.temperature.toString(),
        )
        binding.topP.fill(R.string.settings_top_p, R.string.settings_top_p_help, settings.topP.toString())
        binding.topK.fill(
            R.string.settings_top_k,
            R.string.settings_top_k_help,
            settings.topK.toString(),
            wholeNumber = true,
        )
        binding.repeatPenalty.fill(
            R.string.settings_repeat_penalty,
            R.string.settings_repeat_penalty_help,
            settings.repeatPenalty.toString(),
        )
        binding.contextTokens.fill(
            R.string.settings_context_tokens,
            R.string.settings_context_tokens_help,
            settings.contextTokens.toString(),
            wholeNumber = true,
        )
        binding.maxTokens.fill(
            R.string.settings_max_tokens,
            R.string.settings_max_tokens_help,
            settings.maxResponseTokens.toString(),
            wholeNumber = true,
        )
    }

    private fun ItemGenerationParamBinding.fill(
        nameRes: Int,
        helpRes: Int,
        value: String,
        wholeNumber: Boolean = false,
    ) {
        paramName.setText(nameRes)
        paramHelp.setText(helpRes)
        paramInput.inputType =
            if (wholeNumber) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        paramInput.setText(value)
    }

    private fun save() {
        // Each setter clamps to its own valid range, so a typo lands on the
        // nearest sane value rather than being written through and quietly
        // breaking generation.
        binding.temperature.paramInput.text?.toString()?.trim()?.toDoubleOrNull()?.let { settings.temperature = it }
        binding.topP.paramInput.text?.toString()?.trim()?.toDoubleOrNull()?.let { settings.topP = it }
        binding.topK.paramInput.text?.toString()?.trim()?.toIntOrNull()?.let { settings.topK = it }
        binding.repeatPenalty.paramInput.text?.toString()?.trim()?.toDoubleOrNull()?.let { settings.repeatPenalty = it }
        binding.contextTokens.paramInput.text?.toString()?.trim()?.toIntOrNull()?.let { settings.contextTokens = it }
        binding.maxTokens.paramInput.text?.toString()?.trim()?.toIntOrNull()?.let { settings.maxResponseTokens = it }

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun reset() {
        settings.resetGenerationDefaults()
        bindFields()
        Toast.makeText(this, R.string.settings_generation_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
