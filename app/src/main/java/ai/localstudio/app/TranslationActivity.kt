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
import ai.localstudio.app.models.TranslationModels
import ai.localstudio.core.engine.UserRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Free-text translation, routed through [AppContainer.translationOrchestrator] —
 * a single local GGUF, chosen on the Models screen's Translation tab
 * ([ModelsActivity.Category.TRANSLATION]), prompted for the task rather than
 * run through a dedicated MT model. `memoryEnabled = false` and a fresh,
 * never-persisted `conversationId` per request keep this out of chat history
 * and memory retrieval — a translation isn't a conversation turn worth
 * remembering.
 *
 * Deliberately not [AppContainer.orchestrator] (what [ChatActivity] uses):
 * that one routes to whatever chat is currently configured for — AICore,
 * a cloud provider, or a multi-candidate fallback chain — which answered a
 * real translation request with Gemini Nano because AICore happened to also
 * be enabled for chat, with nothing on this screen explaining why. A
 * dedicated single-local-candidate orchestrator is predictable (always the
 * model this screen says it's using) and, as a side effect, never wrapped in
 * [ai.localstudio.core.runtime.FallbackTextRuntime] — so its "Answer from: X"
 * attribution footer is never appended to the text in the first place, and
 * copying the result copies only the translation.
 *
 * The selected model can be either kind, and they need different prompts:
 * a [TranslationModels] seed (MADLAD-400, a T5 encoder-decoder model — see
 * [ai.localstudio.app.llama.LlamaBridge.nativeGenerateT5]) expects only its
 * own `<2xx> source text` format with no chat framing at all, while an
 * ordinary chat GGUF from [ai.localstudio.app.models.LocalModels] needs the
 * instruction-style prompt [buildChatPrompt] builds. [translate] picks
 * between them by checking whether [Settings.translationModel] names a
 * [TranslationModels] seed — see [buildPrompt].
 *
 * Even with MADLAD-400, Seychellois Creole output is a best-effort draft,
 * not a verified translation the way [PhrasebookActivity]'s pre-checked
 * phrases are — and MADLAD-400 support is new, unverified-on-device native
 * code (see [R.string.models_translation_specialized_note]).
 * [R.string.translation_crs_caveat] says so plainly, for either kind of model.
 */
class TranslationActivity : AppCompatActivity() {

    private enum class Language(val englishName: String, val madladCode: String, val labelRes: Int) {
        RUSSIAN("Russian", "ru", R.string.translation_lang_ru),
        ENGLISH("English", "en", R.string.translation_lang_en),
        CREOLE("Seychellois Creole", "crs", R.string.translation_lang_crs),
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
        binding.sourceLanguageSpinner.onItemSelectedListener = onLanguageChanged
        binding.targetLanguageSpinner.onItemSelectedListener = onLanguageChanged

        binding.swapLanguagesButton.setOnClickListener {
            val source = binding.sourceLanguageSpinner.selectedItemPosition
            val target = binding.targetLanguageSpinner.selectedItemPosition
            binding.sourceLanguageSpinner.setSelection(target)
            binding.targetLanguageSpinner.setSelection(source)
        }

        binding.translateButton.setOnClickListener { translate() }
        binding.copyOutputButton.setOnClickListener { copyOutput() }
        binding.translationModelRow.setOnClickListener {
            startActivity(ModelsActivity.intent(this, ModelsActivity.Category.TRANSLATION))
        }

        updateCaveat()
    }

    override fun onResume() {
        super.onResume()
        // The Translation tab in Models can change settings.translationModel
        // while this screen is stopped underneath it — refreshed here rather
        // than only in onCreate so coming back from picking a model actually
        // shows the pick.
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

    private val onLanguageChanged = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) =
            updateCaveat()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun updateCaveat() {
        binding.translationCaveat.visibility =
            if (selectedLanguage(binding.targetLanguageSpinner) == Language.CREOLE) View.VISIBLE else View.GONE
    }

    private fun updateModelNote() {
        val modelId = container.settings.translationModel.ifBlank { container.settings.chatModelFor(CloudProviders.LOCAL.id) }
        val label = when {
            modelId == CloudProviders.AICORE.id -> getString(CloudProviders.AICORE.titleRes)
            modelId.isNotBlank() -> modelId
            else -> null
        }
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

        val orchestrator = container.translationOrchestrator()
        if (orchestrator == null) {
            Toast.makeText(this, R.string.translation_no_model, Toast.LENGTH_LONG).show()
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
                        orchestrator.handle(
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

    /**
     * [TranslationModels]' own expected format when that's what's selected —
     * MADLAD-400 was fine-tuned on `<2xx> source text` and nothing else; an
     * instruction wrapped around it the way [buildChatPrompt] does would
     * just be more text for the encoder to (mis)translate, not an
     * instruction it understands. Otherwise the chat-instruction prompt, for
     * an ordinary GGUF prompted to translate.
     */
    private fun buildPrompt(source: Language, target: Language, text: String): String =
        if (TranslationModels.SEEDS.any { it.id == container.settings.translationModel }) {
            "<2${target.madladCode}> $text"
        } else {
            buildChatPrompt(source, target, text)
        }

    private fun buildChatPrompt(source: Language, target: Language, text: String): String =
        "You are a translation engine. Translate the text between triple backticks " +
            "from ${source.englishName} to ${target.englishName}. " +
            "Reply with only the translation itself, nothing else — no quotes, no notes, no explanation.\n\n" +
            "```\n$text\n```"

    /**
     * Strips wrapping quotes/backticks a model sometimes adds despite the
     * prompt asking it not to, and — defensively, should this screen ever
     * end up wired to a multi-candidate orchestrator again — a
     * [ai.localstudio.core.runtime.FallbackTextRuntime] attribution footer,
     * which always starts with this exact "\n\n---\n" delimiter
     * ([ai.localstudio.core.runtime.FallbackTextRuntime.attributionFooter]).
     */
    private fun cleanTranslation(raw: String): String {
        var text = raw.substringBefore("\n\n---\n").trim()
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

    /** Copies only the translation — no model name, no language labels. */
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
