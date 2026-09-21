package ai.localstudio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Filter.FilterResults
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.localstudio.app.databinding.ActivityTranslationBinding
import ai.localstudio.app.models.DownloadState
import ai.localstudio.app.models.MadladLanguage
import ai.localstudio.app.models.MadladLanguages
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
 * Languages come from [MadladLanguages] — MADLAD-400's own 417-language
 * table — for both kinds of model this screen can be pointed at: a
 * [TranslationModels] seed (MADLAD-400 itself, a T5 encoder-decoder model —
 * see [ai.localstudio.app.llama.LlamaBridge.nativeGenerateT5]) uses a
 * language's `code` directly in its own `<2xx> source text` format with no
 * chat framing at all, while an ordinary chat GGUF from
 * [ai.localstudio.app.models.LocalModels] gets the instruction-style prompt
 * [buildChatPrompt] builds from a language's English `name`. [translate]
 * picks between the two prompt shapes by checking whether
 * [Settings.translationModel] names a [TranslationModels] seed — see
 * [buildPrompt]. A non-MADLAD model was never trained on most of these 417
 * languages, same caveat as always for a general model asked to translate
 * something it barely saw in training.
 *
 * Even with MADLAD-400, Seychellois Creole output is a best-effort draft,
 * not a verified translation the way [PhrasebookActivity]'s pre-checked
 * phrases are — and MADLAD-400 support is new, unverified-on-device native
 * code (see [R.string.models_translation_specialized_note]).
 * [R.string.translation_crs_caveat] says so plainly, for either kind of model.
 */
class TranslationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranslationBinding
    private lateinit var container: AppContainer
    private lateinit var languages: List<MadladLanguage>
    /**
     * What each language shows as — one word where that's unambiguous
     * (stripping a parenthetical qualifier, e.g. "Myanmar (Burmese)" ->
     * "Myanmar"), the full name where stripping it would make two different
     * languages look identical (e.g. "Kurdish (Kurmanji)" and "Kurdish
     * (Sorani)" both stripping to plain "Kurdish" — that pair keeps its
     * qualifier instead). Never includes the raw code; [LanguageDisplayAdapter]
     * still matches on it, just not shown.
     */
    private lateinit var displayNames: Map<String, String>
    private var translateJob: kotlinx.coroutines.Job? = null

    private var selectedSource: MadladLanguage? = null
    private var selectedTarget: MadladLanguage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranslationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets(applyImeInset = true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.menu_translation)

        container = AppContainer.get(this)
        languages = MadladLanguages.load(this)
        displayNames = buildDisplayNames(languages)

        // A plain ArrayAdapter<String> filters on whole words within the
        // shown text (see its own ArrayFilter) — no help here, since the
        // code that needs to stay searchable ("crs" -> Seychellois Creole)
        // is deliberately not part of what's shown. languageFilter searches
        // the full underlying language list (name and code both) and
        // republishes matches as their display strings instead.
        val displayOf = HashMap<String, MadladLanguage>(languages.size * 2)
        languages.forEach { displayOf[displayNames.getValue(it.code)] = it }
        val languageAdapter = LanguageDisplayAdapter(displayOf.keys.toList())

        binding.sourceLanguageInput.setAdapter(languageAdapter)
        binding.targetLanguageInput.setAdapter(languageAdapter)
        binding.sourceLanguageInput.setOnItemClickListener { parent, _, position, _ ->
            selectedSource = displayOf[parent.getItemAtPosition(position) as String]
            updateCaveat()
        }
        binding.targetLanguageInput.setOnItemClickListener { parent, _, position, _ ->
            selectedTarget = displayOf[parent.getItemAtPosition(position) as String]
            updateCaveat()
        }

        // Russian -> Seychellois Creole is this app's actual use case
        // (Seychelles travel, per docs) — the default the screen opens on,
        // not an arbitrary first entry.
        selectLanguage(binding.sourceLanguageInput, languages.firstOrNull { it.code == "ru" }) { selectedSource = it }
        selectLanguage(binding.targetLanguageInput, languages.firstOrNull { it.code == "crs" }) { selectedTarget = it }

        binding.swapLanguagesButton.setOnClickListener {
            val source = selectedSource
            val target = selectedTarget
            selectLanguage(binding.sourceLanguageInput, target) { selectedSource = it }
            selectLanguage(binding.targetLanguageInput, source) { selectedTarget = it }
        }

        binding.translateButton.setOnClickListener {
            hideKeyboard()
            translate()
        }
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

    /** Sets both the field's displayed text and the backing selection in one place, so they never drift apart. */
    private fun selectLanguage(field: android.widget.AutoCompleteTextView, language: MadladLanguage?, assign: (MadladLanguage?) -> Unit) {
        assign(language)
        field.setText(language?.let { displayNames.getValue(it.code) }.orEmpty(), false)
    }

    /**
     * One word where that's unambiguous, the full name (still no raw code)
     * where two languages would otherwise show identically — see this
     * property's own field-level doc comment on [displayNames].
     */
    private fun buildDisplayNames(languages: List<MadladLanguage>): Map<String, String> {
        fun stripped(name: String) = name.substringBefore(" (").trim()
        val counts = languages.groupingBy { stripped(it.name) }.eachCount()
        return languages.associate { language ->
            val short = stripped(language.name)
            language.code to if (counts.getValue(short) > 1) language.name else short
        }
    }

    /**
     * A plain ArrayAdapter<String> only searches its own displayed items —
     * [languages]' codes are deliberately not part of what's displayed, so
     * this instead searches the underlying [MadladLanguage] list (name and
     * code both) and republishes matches as their [displayNames] strings.
     */
    private inner class LanguageDisplayAdapter(all: List<String>) :
        ArrayAdapter<String>(this@TranslationActivity, android.R.layout.simple_dropdown_item_1line, all.toMutableList()) {

        private val allDisplayNames = all

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val query = constraint?.toString()?.trim()?.lowercase().orEmpty()
                val matches = if (query.isEmpty()) {
                    allDisplayNames
                } else {
                    languages.filter { language ->
                        language.name.lowercase().contains(query) || language.code.lowercase().startsWith(query)
                    }.map { displayNames.getValue(it.code) }
                }
                return FilterResults().apply { values = matches; count = matches.size }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                clear()
                (results?.values as? List<String>)?.let { addAll(it) }
                notifyDataSetChanged()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        currentFocus?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun updateCaveat() {
        binding.translationCaveat.visibility = if (selectedTarget?.code == "crs") View.VISIBLE else View.GONE
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

    /**
     * [container.translationOrchestrator] returns null only when
     * [Settings.translationModel] names nothing installed and nothing else
     * is configured to fall back to — the common case for that being a
     * fresh install, before any translation model has ever been downloaded.
     * Rather than just saying so and leaving the user to find Models ->
     * Translation on their own, offers the flagship pick
     * ([TranslationModels.SEEDS]'s first entry, MADLAD-400) right here.
     */
    private fun offerMadladDownload() {
        val seed = TranslationModels.SEEDS.first()
        if (container.downloads.stateOf(seed) is DownloadState.Installed) {
            // Installed but still not what translationOrchestrator resolved
            // to — settings.translationModel names something else entirely
            // that isn't installed either. Nothing to offer downloading;
            // point at the picker instead.
            Toast.makeText(this, R.string.translation_no_model, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(seed.title)
            .setMessage(getString(R.string.translation_offer_download, seed.title))
            .setPositiveButton(R.string.model_download) { _, _ ->
                NetworkPolicy.confirmIfNeeded(this, container.settings) {
                    container.settings.translationModel = seed.id
                    container.downloads.start(seed)
                    startActivity(ModelsActivity.intent(this, ModelsActivity.Category.TRANSLATION))
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun translate() {
        val text = binding.translationInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return

        val source = selectedSource
        val target = selectedTarget
        if (source == null || target == null) {
            Toast.makeText(this, R.string.translation_no_language, Toast.LENGTH_SHORT).show()
            return
        }
        if (source.code == target.code) {
            Toast.makeText(this, R.string.translation_same_language, Toast.LENGTH_SHORT).show()
            return
        }

        val orchestrator = container.translationOrchestrator()
        if (orchestrator == null) {
            offerMadladDownload()
            return
        }

        translateJob?.cancel()
        setBusy(true)
        // Length only, never the text itself — this log is meant to be
        // copyable and shareable from LogActivity (see AppLog's own doc
        // comment), and a translation request is exactly the kind of
        // content a user would not expect to see in a bug report.
        container.appLog.record("TRANSLATE", "${source.code} -> ${target.code}, ${text.length} chars")

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
    private fun buildPrompt(source: MadladLanguage, target: MadladLanguage, text: String): String =
        if (TranslationModels.SEEDS.any { it.id == container.settings.translationModel }) {
            "<2${target.code}> $text"
        } else {
            buildChatPrompt(source, target, text)
        }

    private fun buildChatPrompt(source: MadladLanguage, target: MadladLanguage, text: String): String =
        "You are a translation engine. Translate the text between triple backticks " +
            "from ${source.name} to ${target.name}. " +
            "Reply with only the translation itself, nothing else — no quotes, no notes, no explanation.\n\n" +
            "```\n$text\n```"

    /**
     * Strips three things: wrapping quotes/backticks a model sometimes adds
     * despite the prompt asking it not to; a
     * [ai.localstudio.core.runtime.FallbackTextRuntime] attribution footer
     * (defensively, should this screen ever end up wired to a
     * multi-candidate orchestrator again), which always starts with this
     * exact "\n\n---\n" delimiter
     * ([ai.localstudio.core.runtime.FallbackTextRuntime.attributionFooter]);
     * and a literal turn-marker token leaking into the text. That last one
     * is a real device report: gemma-4-e4b's official chat template
     * sometimes fails to apply (see [ai.localstudio.app.llama.LlamaCppRuntime]'s
     * own doc comments on that), and native llama.cpp code decides whether a
     * sampled token means "stop" ([llama_vocab_is_eog] in llama_jni.cpp) —
     * for this GGUF, that check doesn't recognize `<end_of_turn>` as one, so
     * it comes out as ordinary text instead of ending generation. Root cause
     * is native and shared with every other chat turn this app generates,
     * not specific to translation; this is the narrow, low-risk half of the
     * fix that actually matters here — a clean copy-paste result — without
     * touching that shared native path.
     */
    private fun cleanTranslation(raw: String): String {
        var text = raw.substringBefore("\n\n---\n").trim()
        for (marker in TURN_MARKERS) text = text.substringBefore(marker).trim()
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

        // Turn-marker tokens a model's own EOG detection sometimes fails to
        // recognize (see cleanTranslation's own doc comment) — covers every
        // chat-template family this app's catalog actually includes
        // (Gemma, Qwen/ChatML, Llama), not just the one seen on-device so far.
        val TURN_MARKERS = listOf("<end_of_turn>", "<|im_end|>", "<|eot_id|>", "<|end|>")
    }
}
