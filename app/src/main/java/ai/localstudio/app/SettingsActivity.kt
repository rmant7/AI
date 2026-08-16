package ai.localstudio.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.localstudio.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val settings = AppContainer.get(this).settings
        binding.endpointInput.setText(settings.endpoint)
        binding.apiKeyInput.setText(settings.apiKey)
        binding.chatModelInput.setText(settings.chatModel)
        binding.asrModelInput.setText(settings.speechModel)

        binding.saveButton.setOnClickListener {
            settings.endpoint = binding.endpointInput.text?.toString().orEmpty()
            settings.apiKey = binding.apiKeyInput.text?.toString().orEmpty()
            settings.chatModel = binding.chatModelInput.text?.toString().orEmpty()
            settings.speechModel = binding.asrModelInput.text?.toString().orEmpty()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
