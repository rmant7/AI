package ai.localstudio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivityTranslationBinding
import ai.localstudio.core.engine.UserRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Free-text translation, routed through the same [ai.localstudio.core.engine.Orchestrator]
 * chat already uses ([AppContainer.orchestrator]) — whichever chat model is
 * currently selected (Settings → Models) answers a one-shot translation
 * prompt instead of a conversational turn. `memoryEnabled = false` and a
 * fresh, never-persisted `conversationId` per request keep this out of chat
 * history and out of memory retrieval: a translation isn't a conversation
 * turn worth remembering, and pulling unrelated memory into the prompt would
 * only add noise here.
 *
 * This is prompting a general-purpose model, not a dedicated translation
 * model — this app's llama.cpp bridge ([ai.localstudio.app.llama.LlamaCppRuntime])
 * only drives decoder-only chat GGUFs today, and a real bilingual engine
 * (MarianMT/OPUS-MT, or a T5 model like MADLAD-400) would need a new native
 * runtime this app doesn't have yet. For en/ru that limitation barely shows;
 * for Seychellois Creole it matters — it's a low-resource language most
 * general models have seen very little of (see [R.string.translation_hint]),
 * so CRS output here is a best-effort starting point, not a verified
 * translation the way [PhrasebookActivity]'s pre-checked phrases are.
 */
class TranslationActivity : AppCompatActivity() {

    private enum class Language(val englishName: String, val labelRes: Int) {
        RUSSIAN("Russian", R.string.translation_lang_ru),
        ENGLISH("English", R.string.translation_lang_en),
        CREOLE("Seychellois Creole", R.string.translation_lang_crs),
    }

    private lateinit var binding: ActivityTranslationBinding
    private lateinit var container: AppContainer
    private var translateJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.menu_translation)

        container = AppContainer.get(this)

        val languageAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            Language.entries.map { getString(it.labelRes) },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.sourceLanguageSpinner.adapter = languageAdapter
        binding.targetLanguageSpinner.adapter = languageAdapter
        // Russian -> Seychellois Creole is this app's actual use case
        // (Seychelles travel, per docs) — the default the screen opens on,
        // not an arbitrary first entry.
        binding.sourceLanguageSpinner.setSelection(Language.RUSSIAN.ordinal)
        binding.targetLanguageSpinner.setSelection(Language.CREOLE.ordinal)

        binding.swapLanguagesButton.setOnClickListener {
            val source = binding.sourceLanguageSpinner.selectedItemPosition
            val target = binding.targetLanguageSpinner.selectedItemPosition
            binding.sourceLanguageSpinner.setSelection(target)
            binding.targetLanguageSpinner.setSelection(source)
        }

        binding.translateButton.setOnClickListener { translate() }
        binding.copyOutputButton.setOnClickListener { copyOutput() }

        updateModelNote()
    }

    override fun onResume() {
        super.onResume()
        updateModelNote()
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

    private fun updateModelNote() {
        val label = container.settings.chatModel.ifBlank { null }
        binding.translationModelNote.text = getString(R.string.translation_model_note, label ?: getString(R.string.translation_model_note_none))
    }

    private fun selectedLanguage(spinner: android.widget.Spinner): Language =
        Language.entries[spinner.selectedItemPosition]

    private fun translate() {
        val text = binding.translationInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val source = selectedLanguage(binding.sourceLanguageSpinner)
        val target = selectedLanguage(binding.targetLanguageSpinner)
        if (source == target) {
            Toast.makeText(this, R.string.translation_same_language, Toast.LENGTH_SHORT).show()
            return
        }

        translateJob?.cancel()
        setBusy(true)
        // Length only, never the text itself — this log is meant to be
        // copyable and shareable from LogActivity (see AppLog's own doc
        // comment), and a translation request is exactly the kind of
        // content a user would not expect to see in a bug report.
        container.appLog.record("TRANSLATE", "${source.englishName} -> ${target.englishName}, ${text.length} chars")

        translateJob = lifecycleScope.launch {
            val prompt = buildPrompt(source, target, text)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeout(GENERATION_TIMEOUT_MS) {
                        container.orchestrator().handle(
                            UserRequest(
                                // Unique per request and never saved to
                                // ChatHistoryStore — this is one-shot, not a
                                // conversation, so there is no earlier turn
                                // for a shared id to collide with anyway.
                                conversationId = "translate-" + System.currentTimeMillis(),
                                text = prompt,
                                memoryEnabled = false,
                            ),
                        )
                    }
                }
            }

            setBusy(false)
            result.onSuccess { answer ->
                container.appLog.record("TRANSLATE", "done, ${answer.text.length} chars back")
                showOutput(cleanTranslation(answer.text))
            }.onFailure { error ->
                container.appLog.record("TRANSLATE", "FAILED: ${error.javaClass.simpleName}: ${error.message}")
                Toast.makeText(
                    this@TranslationActivity,
                    getString(R.string.translation_failed, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun buildPrompt(source: Language, target: Language, text: String): String =
        "You are a translation engine. Translate the text between triple backticks " +
            "from ${source.englishName} to ${target.englishName}. " +
            "Reply with only the translation itself, nothing else — no quotes, no notes, no explanation.\n\n" +
            "```\n$text\n```"

    /** Strips wrapping quotes/backticks a model sometimes adds despite the prompt asking it not to. */
    private fun cleanTranslation(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```") && text.endsWith("```")) text = text.removePrefix("```").removeSuffix("```").trim()
        if (text.length >= 2 && text.first() == text.last() && text.first() in "\"'«»") {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun setBusy(busy: Boolean) {
        binding.translateButton.isEnabled = !busy
        binding.translationProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showOutput(text: String) {
        binding.translationOutput.text = text
        binding.translationOutputCard.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun copyOutput() {
        val text = binding.translationOutput.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.menu_translation), text))
        Toast.makeText(this, R.string.translation_copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val GENERATION_TIMEOUT_MS = 120_000L
    }
}
