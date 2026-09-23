package ai.localstudio.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
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
import ai.localstudio.app.databinding.ItemTranslationResultBinding
import ai.localstudio.app.llama.GenerationKeepAliveService
import ai.localstudio.app.models.DownloadState
import ai.localstudio.app.models.MadladLanguage
import ai.localstudio.app.models.MadladLanguages
import ai.localstudio.app.models.TranslationModels
import ai.localstudio.app.models.TtsVoiceFallback
import ai.localstudio.core.engine.UserRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Free-text translation, one card per source from
 * [AppContainer.translationCompareCandidates] — every provider enabled in
 * Settings, plus [CloudProviders.AICORE] always, whether or not it's
 * separately enabled there: Gemini Nano costs nothing to try and answers in
 * seconds. Each source is prompted for the task rather than run through a
 * dedicated MT model, except the LOCAL entry when it names a
 * [TranslationModels] seed — see [buildPrompt]. `memoryEnabled = false` and a
 * fresh, never-persisted `conversationId` per request keep every source out
 * of chat history and memory retrieval — a translation isn't a conversation
 * turn worth remembering.
 *
 * Deliberately not [AppContainer.orchestrator] (what [ChatActivity] uses):
 * that one routes to whatever chat is currently configured for, tried as a
 * *fallback chain* rather than shown side by side — a real translation
 * request answered with Gemini Nano because AICore happened to also be
 * enabled for chat, with nothing on this screen explaining why. Same as
 * [AppContainer.compareCandidates], every source here is wrapped in
 * [ai.localstudio.core.runtime.FallbackTextRuntime] even when it is a single
 * candidate, for the "Answer from: X · Ns" attribution — [cleanTranslation]
 * strips that footer before a card shows or copies its own result.
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

    /**
     * Speaks a card's own result aloud, in [selectedTarget]'s language —
     * every card in one translate() batch shares the same target, so this
     * is initialized once per Activity, not per card. Null until
     * [TextToSpeech]'s own async init callback fires; a tap before then (or
     * one that finds no usable voice — see [ttsLocaleFor]) just shows
     * [R.string.translation_speak_unavailable] instead of speaking.
     */
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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
            setSource(displayOf[parent.getItemAtPosition(position) as String])
            updateCaveat()
        }
        binding.targetLanguageInput.setOnItemClickListener { parent, _, position, _ ->
            setTarget(displayOf[parent.getItemAtPosition(position) as String])
            updateCaveat()
        }

        // Restored from Settings — real device report: navigating to Chat
        // and back reset this screen to a hardcoded default, discarding
        // whatever pair (Russian -> Hebrew, in that report) was actually
        // last in use, the same in-memory-field-only gap translationDraftText
        // already closed for the input text. Russian -> Seychellois Creole
        // (Seychelles travel, per docs) is only the very first-launch
        // default, when nothing has been picked yet.
        val defaultSourceCode = container.settings.translationSourceLang.ifBlank { "ru" }
        val defaultTargetCode = container.settings.translationTargetLang.ifBlank { "crs" }
        selectLanguage(binding.sourceLanguageInput, languages.firstOrNull { it.code == defaultSourceCode }) { setSource(it) }
        selectLanguage(binding.targetLanguageInput, languages.firstOrNull { it.code == defaultTargetCode }) { setTarget(it) }

        binding.swapLanguagesButton.setOnClickListener {
            val source = selectedSource
            val target = selectedTarget
            selectLanguage(binding.sourceLanguageInput, target) { setSource(it) }
            selectLanguage(binding.targetLanguageInput, source) { setTarget(it) }
        }

        binding.translateButton.setOnClickListener {
            hideKeyboard()
            translate()
        }
        binding.translationModelRow.setOnClickListener {
            startActivity(ModelsActivity.intent(this, ModelsActivity.Category.TRANSLATION))
        }

        // Restored from Settings, not just left as whatever this Activity
        // instance's own field happened to hold — a process kill (a real
        // risk this app already has under memory pressure) loses that, and
        // with it whatever the user had typed and not yet translated.
        binding.translationInput.setText(container.settings.translationDraftText)
        binding.translationInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                container.settings.translationDraftText = s?.toString().orEmpty()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        tts = TextToSpeech(this) { status -> ttsReady = status == TextToSpeech.SUCCESS }

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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
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

    /** [selectedSource]/[selectedTarget]'s only two setters — every path that changes either goes through one of these, so persistence can't be forgotten at a new call site. */
    private fun setSource(language: MadladLanguage?) {
        selectedSource = language
        container.settings.translationSourceLang = language?.code.orEmpty()
    }

    private fun setTarget(language: MadladLanguage?) {
        selectedTarget = language
        container.settings.translationTargetLang = language?.code.orEmpty()
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
     * [container.translationCompareCandidates] returns an empty list only
     * when [Settings.translationModel] names nothing installed and even
     * Gemini Nano's own candidate didn't build — practically only a fresh
     * install, before any translation model has ever been downloaded.
     * Rather than just saying so and leaving the user to find Models ->
     * Translation on their own, offers the flagship pick
     * ([TranslationModels.SEEDS]'s first entry, MADLAD-400) right here.
     */
    private fun offerMadladDownload() {
        val seed = TranslationModels.SEEDS.first()
        if (container.downloads.stateOf(seed) is DownloadState.Installed) {
            // Installed but still not what translationLocalCandidate
            // resolved to — settings.translationModel names something else
            // entirely that isn't installed either. Nothing to offer downloading;
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

        val sources = container.translationCompareCandidates()
        if (sources.isEmpty()) {
            // Only reachable when even Gemini Nano's own candidate somehow
            // didn't build — translationCompareCandidates always includes
            // it, so this is effectively a "nothing at all" fallback, same
            // as the pre-compare single-orchestrator screen had.
            offerMadladDownload()
            return
        }

        translateJob?.cancel()
        setBusy(true)
        binding.translationResultsContainer.removeAllViews()
        // Length only, never the text itself — this log is meant to be
        // copyable and shareable from LogActivity (see AppLog's own doc
        // comment), and a translation request is exactly the kind of
        // content a user would not expect to see in a bug report.
        container.appLog.record("TRANSLATE", "${source.code} -> ${target.code}, ${text.length} chars, ${sources.size} source(s)")

        // Same reasoning as ChatActivity.sendCompare()'s own keepAlive: a
        // device report showed a local translate() call never finishing
        // after the app fell out of the foreground LRU bucket. One shared
        // flag for the whole batch — only the local source (if any) needs
        // it, a cloud/AICore source is network-bound, not CPU-bound.
        val keepAlive = sources.any { it.isLocal }
        translateJob = lifecycleScope.launch {
            if (keepAlive) GenerationKeepAliveService.begin(this@TranslationActivity)
            try {
                // Every card added up front on Main, before any async work
                // starts — same reasoning as ChatActivity.sendCompare(): no
                // two sources race to mutate translationResultsContainer.
                val cards = sources.map { addResultCard(it.label) }
                val jobs = sources.mapIndexed { index, translationSource ->
                    async(Dispatchers.IO) {
                        val prompt = buildPrompt(translationSource, source, target, text)
                        val result = runCatching {
                            withTimeout(GENERATION_TIMEOUT_MS) {
                                translationSource.orchestrator.handle(
                                    UserRequest(
                                        // Unique per request and never saved to
                                        // ChatHistoryStore — this is one-shot, not
                                        // a conversation, so there is no earlier
                                        // turn for a shared id to collide with.
                                        conversationId = "translate-" + System.currentTimeMillis() + "-" + index,
                                        text = prompt,
                                        memoryEnabled = false,
                                    ),
                                )
                            }
                        }
                        withContext(Dispatchers.Main) {
                            result.onSuccess { answer ->
                                container.appLog.record(
                                    "TRANSLATE",
                                    "${translationSource.label}: done, ${answer.text.length} chars back",
                                )
                                cards[index].resultText.text = cleanTranslation(answer.text)
                            }.onFailure { error ->
                                container.appLog.record(
                                    "TRANSLATE",
                                    "${translationSource.label}: FAILED: ${error.javaClass.simpleName}: ${error.message}",
                                )
                                cards[index].resultText.text = if (error is kotlinx.coroutines.TimeoutCancellationException) {
                                    getString(R.string.chat_compare_timeout_error, GENERATION_TIMEOUT_MS / 1000)
                                } else {
                                    getString(R.string.chat_compare_generic_error, error.message ?: error.toString())
                                }
                            }
                        }
                    }
                }
                jobs.awaitAll()
            } finally {
                setBusy(false)
                if (keepAlive) GenerationKeepAliveService.end(this@TranslationActivity)
            }
        }
    }

    /** One card per source, filled in as its own translation arrives — see [translate]. */
    private fun addResultCard(label: String): ItemTranslationResultBinding {
        val card = ItemTranslationResultBinding.inflate(LayoutInflater.from(this), binding.translationResultsContainer, true)
        card.resultLabel.text = label
        card.resultText.text = "…"
        card.resultCopyButton.setOnClickListener { copyText(card.resultText.text?.toString().orEmpty()) }
        card.resultSpeakButton.setOnClickListener { speak(card.resultText.text?.toString().orEmpty()) }
        return card
    }

    /**
     * Speaks [text] aloud in [selectedTarget]'s language — every card in one
     * batch shares that same target (see [translate]), so this asks once
     * per tap rather than needing to know which card it came from.
     */
    private fun speak(text: String) {
        if (text.isBlank() || text == "…") return
        val engine = tts
        if (engine == null || !ttsReady) {
            Toast.makeText(this, R.string.translation_speak_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val locale = selectedTarget?.code?.let { ttsLocaleFor(engine, it) }
        if (locale == null) {
            Toast.makeText(this, R.string.translation_speak_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        engine.language = locale
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translation")
    }

    /**
     * [code] itself if [engine] has a voice for it, otherwise
     * [TtsVoiceFallback]'s closest-sounding substitute if [engine] has a
     * voice for *that* — null when neither does, which [speak] shows as
     * [R.string.translation_speak_unavailable] rather than a silent no-op.
     */
    private fun ttsLocaleFor(engine: TextToSpeech, code: String): java.util.Locale? {
        val direct = java.util.Locale(code)
        if (engine.isLanguageAvailable(direct) >= TextToSpeech.LANG_AVAILABLE) return direct
        val fallback = TtsVoiceFallback.closestAvailable(code)?.let { java.util.Locale(it) } ?: return null
        return fallback.takeIf { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
    }

    /**
     * [TranslationModels]' own expected format, only for the LOCAL source
     * when it names one of those seeds — MADLAD-400 was fine-tuned on
     * `<2xx> source text` and nothing else; an instruction wrapped around it
     * the way [buildChatPrompt] does would just be more text for the encoder
     * to (mis)translate, not an instruction it understands. No other source
     * (AICore, a cloud provider) is ever a T5 model, so every one of those
     * always gets the chat-instruction prompt regardless of what the LOCAL
     * source happens to be.
     */
    private fun buildPrompt(translationSource: AppContainer.CompareSource, source: MadladLanguage, target: MadladLanguage, text: String): String =
        if (translationSource.isLocal && TranslationModels.SEEDS.any { it.id == container.settings.translationModel }) {
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

    /** One card's own copy button — copies only that card's translation, no label. */
    private fun copyText(text: String) {
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
