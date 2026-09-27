package ai.localstudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityModelsBinding
import ai.localstudio.app.databinding.ItemLocalModelBinding
import ai.localstudio.app.databinding.ItemModelBinding
import ai.localstudio.app.llama.EmbeddingModelSpec
import ai.localstudio.app.llama.ExperimentalDownloadState
import ai.localstudio.app.llama.ExperimentalEmbeddingModels
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.models.DownloadState
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.ModelPurpose
import ai.localstudio.app.models.TranslationModels
import ai.localstudio.app.vosk.VoskDownloadState
import ai.localstudio.app.vosk.VoskModelSeed
import ai.localstudio.app.vosk.VoskModelStore
import ai.localstudio.app.vosk.VoskModels
import ai.localstudio.app.whisper.WhisperDownloadState
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.ModelFit
import ai.localstudio.core.speech.AsrEngineType
import kotlinx.coroutines.launch

/**
 * Every model the app can run, grouped by what it is *for* — chat models,
 * voice models and the embedding model are one catalog under a task
 * switcher, the way Edge Gallery organises its own. Voice models used to be
 * buried in Settings, which made them feel like a different kind of thing
 * than the chat models they sit beside conceptually; the embedding model
 * used to live inside the Text tab for the same reason it shouldn't have —
 * see [Category.EMBEDDING].
 *
 * Image and agent categories are the reason [Category] is an enum rather than
 * a boolean: adding one is a new entry and a new row builder, not a rewrite.
 *
 * The fit label is advice, not a gate: it is the user's device, and a list
 * whose interesting entries are read-only descriptions is a catalogue of
 * things you cannot have. What the label does is set expectations before a
 * multi-gigabyte download.
 */
class ModelsActivity : AppCompatActivity() {

    /**
     * [EMBEDDING] is [ai.localstudio.app.llama.ExperimentalEmbeddingModels.E5_BASE]
     * only — the app's one production semantic-memory model, not a chat
     * model a user could pick to answer with. It used to sit as a plain row
     * at the bottom of [TEXT], which is exactly the "another model to
     * choose between" framing this tab exists to avoid: nothing here ever
     * changes [Settings.chatModel].
     */
    enum class Category { TEXT, VOICE, EMBEDDING, TRANSLATION }

    private lateinit var binding: ActivityModelsBinding
    private lateinit var container: AppContainer
    private val adapter = RowAdapter()

    private var category = Category.TEXT

    // A denial here does not block downloads — it only means the foreground
    // service's progress notification stays invisible, so no fallback is needed.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)
        binding.models.layoutManager = LinearLayoutManager(this)
        binding.models.adapter = adapter

        // Deep-linked here (e.g. Settings' Whisper "Manage" button, or
        // Memory's "Manage" link) rather than always landing on Text —
        // opening on the wrong tab and making the user re-tap defeats the
        // point of a direct link.
        when (intent.getStringExtra(EXTRA_CATEGORY)) {
            Category.VOICE.name -> {
                category = Category.VOICE
                binding.categoryToggle.check(R.id.categoryVoice)
            }
            Category.EMBEDDING.name -> {
                category = Category.EMBEDDING
                binding.categoryToggle.check(R.id.categoryEmbedding)
            }
            Category.TRANSLATION.name -> {
                category = Category.TRANSLATION
                binding.categoryToggle.check(R.id.categoryTranslation)
            }
        }

        binding.categoryToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            category = when (checkedId) {
                R.id.categoryVoice -> Category.VOICE
                R.id.categoryEmbedding -> Category.EMBEDDING
                R.id.categoryTranslation -> Category.TRANSLATION
                else -> Category.TEXT
            }
            render()
            // Real device report: switching to Translation landed scrolled
            // to wherever the previously-shown category (Text, with its own
            // much longer general chat-model list) happened to leave the
            // RecyclerView, well past the specialized MADLAD-400 section
            // this tab actually leads with — a plain notifyDataSetChanged()
            // (inside render(), via DiffUtil) never resets scroll position
            // on its own. Every render() from here on (a download's own
            // progress ticking, for one) should still leave scroll alone;
            // this only fires on an actual tab switch.
            binding.models.scrollToPosition(0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch { container.downloads.state.collect { render() } }
        lifecycleScope.launch { container.whisperDownloads.state.collect { render() } }
        lifecycleScope.launch { container.voskDownloads.state.collect { render() } }
        lifecycleScope.launch { container.experimentalEmbeddingDownloads.state.collect { render() } }
        lifecycleScope.launch { container.aicoreStatus.collect { render() } }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
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

    // ── Text models ────────────────────────────────────────────────────────

    private fun onTextPrimary(seed: LocalModelSeed) {
        when (container.downloads.stateOf(seed)) {
            is DownloadState.Installed -> useLocally(seed)
            is DownloadState.Running, is DownloadState.Resolving -> container.downloads.cancel(seed)
            else -> NetworkPolicy.confirmIfNeeded(this, container.settings) { container.downloads.start(seed) }
        }
    }

    private fun onTextSecondary(seed: LocalModelSeed) {
        if (seed.isCustom) {
            removeCustomSeed(seed, ModelPurpose.CHAT)
            return
        }
        when (val state = container.downloads.stateOf(seed)) {
            is DownloadState.Failed -> showDetails(seed.title, state.message)
            DownloadState.Idle -> hideModel(seed)
            else -> {
                container.downloads.delete(seed)
                render()
            }
        }
    }

    /**
     * Dismisses a catalog seed from every list that offers it
     * ([Settings.hiddenModelIds] is shared between the Text and Translation
     * tabs — [LocalModels.SEEDS] appears on both). Real device feedback: the
     * catalog is too long to scroll past on a phone that can't fit most of
     * it anyway, and unlike a custom model a catalog seed had no way to be
     * removed from view at all — only [DownloadState.Idle] offers this,
     * never something already downloaded or mid-download, so hiding can
     * never make an installed or in-progress model disappear out from under
     * the user. [restoreHiddenModels] undoes this, all at once.
     */
    private fun hideModel(seed: LocalModelSeed) {
        container.settings.hiddenModelIds = container.settings.hiddenModelIds + seed.id
        container.appLog.record("MODELS", "${seed.id}: hidden from the catalog list")
        render()
    }

    private fun restoreHiddenModels() {
        container.settings.hiddenModelIds = emptySet()
        render()
    }

    /** Shared by [textRows] and [translationRows] — one hidden set, restored all at once. */
    private fun MutableList<Row>.addHiddenModelsRow() {
        val hiddenCount = container.settings.hiddenModelIds.size
        if (hiddenCount == 0) return
        add(
            Row.Model(
                title = getString(R.string.models_hidden_title),
                subtitle = getString(R.string.models_hidden_subtitle, hiddenCount),
                selected = false,
                status = null,
                progress = null,
                indeterminate = false,
                primaryLabel = getString(R.string.models_hidden_restore),
                primaryEnabled = true,
                secondaryLabel = null,
                onPrimary = { restoreHiddenModels() },
                onSecondary = {},
            ),
        )
    }

    /**
     * Switching the provider is what makes the downloaded model answer.
     *
     * A model that doesn't fit the RAM budget isn't refused outright — see
     * the class doc on why — but it fails in a way a confirmation is worth
     * interrupting for: Android's low-memory killer terminates the process
     * outright once it's loaded and generating, no exception, no dialog, just
     * gone. That is a worse experience than one extra tap for anyone who
     * would have picked a smaller model had they known.
     */
    private fun useLocally(seed: LocalModelSeed) {
        if (needsRamWarning(seed.approxSizeBytes)) {
            AlertDialog.Builder(this)
                .setTitle(seed.title)
                .setMessage(ramWarningMessage(seed.approxSizeBytes))
                .setPositiveButton(R.string.model_ram_warning_continue) { _, _ -> switchToLocal(seed) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        switchToLocal(seed)
    }

    /**
     * Two different questions, both worth warning about before a switch that
     * fails silently: does this clear the configured RAM budget at all
     * ([DeviceProfile.fitsBudget], a stable policy ceiling), and — real
     * device report — could it actually load *right now*
     * ([DeviceProfile.fitsLiveMemory], live free memory). A model can pass
     * the first and fail the second: switching through three different
     * MADLAD-400 10B quantisations in a row, none of them warned about here,
     * every one then rejected seconds later by the load-time admission
     * check that already uses live memory deliberately (see
     * [DeviceProfile.fitsLiveMemory]'s own doc comment) — with nothing in
     * between to tell the user beforehand.
     */
    private fun needsRamWarning(approxSizeBytes: Long): Boolean =
        !container.device.fitsBudget(approxSizeBytes) || !container.device.fitsLiveMemory(approxSizeBytes)

    private fun ramWarningMessage(approxSizeBytes: Long): String {
        val device = container.device
        val estimate = approxSizeBytes * DeviceProfile.ESTIMATE_NUMERATOR / DeviceProfile.ESTIMATE_DENOMINATOR
        // fitsBudget can still be true here (that's exactly needsRamWarning's
        // second case) — the headline and the number quoted both need to
        // match whichever check actually failed, or the message reads as
        // contradicting itself ("exceeds your budget" next to a budget
        // figure comfortably above the estimate).
        return if (!device.fitsBudget(approxSizeBytes)) {
            getString(R.string.model_ram_warning) + "\n\n" +
                getString(R.string.model_ram_warning_numbers, size(estimate), size(device.usableRamBytes))
        } else {
            getString(R.string.model_ram_warning_live) + "\n\n" +
                getString(R.string.model_ram_warning_numbers, size(estimate), size(device.liveRamBytes))
        }
    }

    private fun switchToLocal(seed: LocalModelSeed) {
        logModelSwitch("chat", seed.title, seed.approxSizeBytes)
        container.settings.providerId = CloudProviders.LOCAL.id
        container.settings.chatModel = seed.id
        Toast.makeText(this, getString(R.string.models_switched_chat, seed.title), Toast.LENGTH_SHORT).show()
        render()
    }

    /**
     * Real device report: a 9B chat model (~5.5 GB on disk) switched to for
     * translation with no warning at all — unlike [useLocally] above, this
     * used to write [Settings.translationModel] outright — then failed the
     * first time it was actually asked to translate, deep inside
     * [ai.localstudio.core.registry.SuitabilityScorer]'s own RAM rejection,
     * reading as "no model" (see [TranslationActivity.translate]'s own
     * comment on that exact exception). Same confirm-before-switching gate
     * [useLocally] already has, so the warning lands at the moment a smaller
     * model could still be picked instead, not after a confusing failure.
     */
    private fun logModelSwitch(purpose: String, title: String, approxSizeBytes: Long) {
        val device = container.device
        container.appLog.record(
            "MODEL_SWITCH",
            "$purpose: $title — size=" +
                (if (approxSizeBytes > 0) size(approxSizeBytes) else "n/a") +
                " fitsBudget=${device.fitsBudget(approxSizeBytes)} usableRamBudget=${size(device.usableRamBytes)}" +
                " fitsLiveMemory=${device.fitsLiveMemory(approxSizeBytes)} liveRamBudget=${size(device.liveRamBytes)} — " +
                AppContainer.currentMemoryDiagnostics(this),
        )
    }

    // ── Translation model ─────────────────────────────────────────────────
    //
    // Three kinds of candidate, all picked the same way (tap "Use", stored in
    // [Settings.translationModel]) but fetched/run differently: [TranslationModels]
    // (a specialized T5 GGUF), [LocalModels] (an ordinary chat GGUF prompted
    // for the task), and AICore/Gemini Nano — the last one shares
    // [AppContainer.downloads] with nothing, since there is no file to fetch;
    // it is either available on this device or it isn't, discovered only
    // when actually asked (see [AppContainer.translationLocalCandidate]'s own
    // doc comment). What this tab adds over just using the chat model as-is:
    // the choice is independent of [Settings.chatModel] — pick a different
    // model (or Gemini Nano) for translation without changing what chat
    // answers with.

    private fun onTranslationPrimary(seed: LocalModelSeed) {
        when (container.downloads.stateOf(seed)) {
            is DownloadState.Installed -> useForTranslationLocal(seed)
            is DownloadState.Running, is DownloadState.Resolving -> container.downloads.cancel(seed)
            else -> NetworkPolicy.confirmIfNeeded(this, container.settings) { container.downloads.start(seed) }
        }
    }

    /** Same confirm-before-switching gate [useLocally] has — see [logModelSwitch]'s own comment. */
    private fun useForTranslationLocal(seed: LocalModelSeed) {
        if (needsRamWarning(seed.approxSizeBytes)) {
            AlertDialog.Builder(this)
                .setTitle(seed.title)
                .setMessage(ramWarningMessage(seed.approxSizeBytes))
                .setPositiveButton(R.string.model_ram_warning_continue) { _, _ ->
                    useForTranslation(seed.id, seed.title, seed.approxSizeBytes)
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        useForTranslation(seed.id, seed.title, seed.approxSizeBytes)
    }

    private fun onTranslationSecondary(seed: LocalModelSeed) {
        if (seed.isCustom) {
            removeCustomSeed(seed, ModelPurpose.TRANSLATION)
            return
        }
        when (val state = container.downloads.stateOf(seed)) {
            is DownloadState.Failed -> showDetails(seed.title, state.message)
            DownloadState.Idle -> hideModel(seed)
            else -> {
                container.downloads.delete(seed)
                render()
            }
        }
    }

    /**
     * Forgets a custom seed entirely — its repo id in
     * [Settings.customModelRepoIds], not just a downloaded file — regardless
     * of [DownloadState]: real device report, a custom download that failed
     * (or one the user simply changed their mind about mid-download) had no
     * way to be removed from the list within the same session, since a
     * catalog seed's own secondary button only ever offered "Details" for a
     * [DownloadState.Failed] row.
     */
    private fun removeCustomSeed(seed: LocalModelSeed, purpose: ModelPurpose?) {
        // Selection first, list second: nothing — this screen's next render,
        // or a translation/chat turn racing it — may ever see a selection
        // pointing at a model id that no longer exists. A dangling one
        // doesn't fail loudly: effectiveLocalSelection just finds nothing
        // installed under it and the turn silently falls through to
        // whichever candidate comes next, which reads as "the answer came
        // from a different model than the one I picked". Blank means "auto"
        // for both settings, same as a fresh install.
        val settings = container.settings
        if (purpose == ModelPurpose.TRANSLATION && settings.translationModel == seed.id) {
            settings.translationModel = ""
            container.appLog.record("MODELS", "${seed.id}: removed while selected for translation — selection reset to auto")
        }
        if (purpose == ModelPurpose.CHAT && settings.chatModelFor(CloudProviders.LOCAL.id) == seed.id) {
            settings.setChatModelFor(CloudProviders.LOCAL.id, "")
            container.appLog.record("MODELS", "${seed.id}: removed while selected for chat — selection reset to default")
        }
        container.removeCustomModel(seed.repoIds.first(), purpose)
        render()
    }

    private fun useForTranslation(modelId: String, title: String, approxSizeBytes: Long = 0L) {
        logModelSwitch("translation", title, approxSizeBytes)
        container.settings.translationModel = modelId
        Toast.makeText(this, getString(R.string.models_switched_translation, title), Toast.LENGTH_SHORT).show()
        render()
    }

    // ── Voice models ───────────────────────────────────────────────────────

    private fun onVoicePrimary(seed: WhisperModelSeed) {
        when (container.whisperDownloads.stateOf(seed)) {
            is WhisperDownloadState.Installed -> useForVoice(seed)
            is WhisperDownloadState.Running -> container.whisperDownloads.cancel(seed)
            else -> NetworkPolicy.confirmIfNeeded(this, container.settings) { container.whisperDownloads.start(seed) }
        }
    }

    private fun onVoiceSecondary(seed: WhisperModelSeed) {
        if (seed.isCustom) {
            container.removeCustomWhisperModel(seed.modelUrl)
            render()
            return
        }
        when (val state = container.whisperDownloads.stateOf(seed)) {
            is WhisperDownloadState.Failed -> showDetails(seed.title, state.message)
            else -> {
                container.whisperDownloads.delete(seed)
                render()
            }
        }
    }

    private fun useForVoice(seed: WhisperModelSeed) {
        logModelSwitch("voice", seed.title, seed.approxSizeBytes)
        container.settings.whisperModelId = seed.id
        container.settings.activeSttEngine = AsrEngineType.WHISPER
        Toast.makeText(this, getString(R.string.models_switched_voice, seed.title), Toast.LENGTH_SHORT).show()
        render()
    }

    // ── Vosk models (docs/14-vosk-spike.md) ───────────────────────────────

    private fun onVoskPrimary(seed: VoskModelSeed) {
        when (container.voskDownloads.stateOf(seed)) {
            is VoskDownloadState.Installed -> useForVosk(seed)
            is VoskDownloadState.Running -> container.voskDownloads.cancel(seed)
            else -> NetworkPolicy.confirmIfNeeded(this, container.settings) { container.voskDownloads.start(seed) }
        }
    }

    private fun onVoskSecondary(seed: VoskModelSeed) {
        when (val state = container.voskDownloads.stateOf(seed)) {
            is VoskDownloadState.Failed -> showDetails(seed.title, state.message)
            else -> {
                container.voskDownloads.delete(seed)
                render()
            }
        }
    }

    private fun useForVosk(seed: VoskModelSeed) {
        logModelSwitch("voice", seed.title, seed.approxSizeBytes)
        container.settings.voskModelId = seed.id
        container.settings.activeSttEngine = AsrEngineType.VOSK
        Toast.makeText(this, getString(R.string.models_switched_voice, seed.title), Toast.LENGTH_SHORT).show()
        render()
    }

    private fun showDetails(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    private fun render() {
        val device = container.device
        binding.deviceText.text = describeDevice(device)
        adapter.submit(
            when (category) {
                Category.VOICE -> voiceRows(device)
                Category.EMBEDDING -> embeddingRows()
                Category.TEXT -> textRows(device)
                Category.TRANSLATION -> translationRows(device)
            },
        )
    }

    private fun textRows(device: DeviceProfile): List<Row> = buildList {
        if (!LlamaBridge.isAvailable) add(Row.Header(getString(R.string.model_native_missing)))
        addHiddenModelsRow()
        add(Row.Header(getString(R.string.models_local_header)))

        val freshness = container.catalogFreshness.cached()
        val hidden = container.settings.hiddenModelIds

        // Already-resident models first (an actual answer, right now, with
        // no load to wait through or risk failing), then models that merely
        // fit the budget, over-budget ones last — sortedWith is stable, so
        // within each of those three groups the catalog's own order still
        // holds. Real device report: several models showing "Recommended"
        // would still refuse to load once the RAM budget was turned down,
        // scattered through the list with no way to tell which ones were
        // actually pickable without opening each in turn.
        //
        // A custom seed is never filtered by hidden here — it has its own
        // Remove action (see hideModel's own doc comment), and its id space
        // (custom-...) doesn't overlap a catalog id's anyway.
        val sortedSeeds = (LocalModels.SEEDS.filter { it.id !in hidden } + container.customSeeds(ModelPurpose.CHAT))
            .sortedWith(
                compareByDescending<LocalModelSeed> { container.isModelResident(it.id) }
                    .thenByDescending { device.fitsBudget(it.approxSizeBytes) },
            )
        sortedSeeds.forEach { seed ->
            val state = container.downloads.stateOf(seed)
            val fitsBudget = device.fitsBudget(seed.approxSizeBytes)
            // Real device report: the ✓ (selected) mark reads as "this is
            // using RAM right now" — it never meant that, only "this is what
            // Settings points to". isModelResident actually answers "using
            // RAM right now", separately from selected, since a model can be
            // selected but evicted (translation freed for a chat load, or
            // vice versa), or resident-and-idle without being the current
            // selection at all.
            val isLoadedNow = container.isModelResident(seed.id)
            // Real device report: settings.chatModel kept pointing at a model
            // that doesn't fit the budget (chosen anyway through the warning
            // dialog, or before the budget was turned down) — the row showed
            // its ✓ and "Installed" the same as a model that actually works,
            // even though every real attempt to use it fails on load with
            // "Not enough free RAM". requiring fitsBudget here is what makes
            // the checkmark mean "this is what will actually answer", not
            // just "this is what Settings happens to point to" — tapping Use
            // again re-shows the warning and, once accepted, still ends up
            // right back here, since nothing about the RAM budget changed.
            val selected = container.settings.providerId == CloudProviders.LOCAL.id &&
                container.settings.chatModel == seed.id && fitsBudget
            // Checked at the last app launch, not at render time — this is
            // what "недоступен" means below: at least one source 404'd or
            // was gated the last time this catalogue was refreshed, before
            // the user ever tapped Download.
            val knownStale = freshness[seed.id]?.ok == false && state !is DownloadState.Installed

            add(
                Row.Model(
                    title = seed.title,
                    subtitle = buildString {
                        append(seed.paramsLabel)
                        // Includes the projector's size for a vision-capable
                        // seed — it downloads too, and quoting only the main
                        // GGUF here left the actual install noticeably
                        // bigger than what this line promised upfront.
                        val totalApproxBytes = seed.approxSizeBytes + seed.mmprojApproxSizeBytes
                        if (totalApproxBytes > 0) append(" · ~${size(totalApproxBytes)}")
                        append(" · ").append(fitLabel(device.classifyFit(seed.approxSizeBytes.takeIf { it > 0 } ?: 1)))
                        if (!fitsBudget) append(" · ").append(getString(R.string.model_exceeds_ram_budget))
                        if (isLoadedNow) append(" · ").append(getString(R.string.model_loaded_now))
                        if (knownStale) append(" · ").append(getString(R.string.model_catalog_stale))
                        append("\n").append(seed.resolvedNote(this@ModelsActivity))
                    },
                    selected = selected,
                    status = textStatus(state, container.modelStore.installedSize(seed)),
                    progress = downloadProgress(state, seed),
                    indeterminate = state is DownloadState.Resolving,
                    primaryLabel = when (state) {
                        is DownloadState.Installed ->
                            getString(if (selected) R.string.model_installed else R.string.model_use)
                        is DownloadState.Running -> getString(R.string.model_pause)
                        is DownloadState.Paused -> getString(R.string.model_resume)
                        is DownloadState.Resolving -> getString(R.string.model_cancel)
                        is DownloadState.Failed -> getString(R.string.model_retry)
                        DownloadState.Idle -> getString(R.string.model_download)
                    },
                    primaryEnabled = !(state is DownloadState.Installed && selected),
                    // isCustom: always removable, in every state — see
                    // removeCustomSeed's own doc comment. A catalog seed
                    // offers Hide only when Idle — see hideModel's own doc
                    // comment on why never for something downloaded or
                    // mid-download.
                    secondaryLabel = if (seed.isCustom) {
                        getString(R.string.model_remove)
                    } else {
                        when (state) {
                            is DownloadState.Failed -> getString(R.string.model_details)
                            is DownloadState.Installed -> getString(R.string.model_delete)
                            is DownloadState.Paused -> getString(R.string.model_delete)
                            DownloadState.Idle -> getString(R.string.model_hide)
                            else -> null
                        }
                    },
                    onPrimary = { onTextPrimary(seed) },
                    onSecondary = { onTextSecondary(seed) },
                    warnsOverBudget = !fitsBudget,
                    loadedNow = isLoadedNow,
                ),
            )
        }

        // Files a past version's catalog knew about but this one doesn't —
        // observed for real after switching branches during development
        // (a different runtime's model format, .litertlm, left behind by a
        // branch this build no longer includes at all). Switching branches
        // or versions never touches app-private storage on its own, so
        // without this such a file just sits there, invisible and
        // undeletable through the app, for as long as it stays installed.
        val orphans = container.modelStore.orphanedFiles(LocalModels.SEEDS + TranslationModels.SEEDS + container.allCustomSeeds())
        if (orphans.isNotEmpty()) {
            val totalBytes = orphans.sumOf { it.length() }
            add(
                Row.Model(
                    title = getString(R.string.models_orphans_title),
                    subtitle = getString(R.string.models_orphans_subtitle, orphans.size, size(totalBytes)),
                    selected = false,
                    status = null,
                    progress = null,
                    indeterminate = false,
                    primaryLabel = getString(R.string.models_orphans_delete, size(totalBytes)),
                    primaryEnabled = true,
                    secondaryLabel = null,
                    onPrimary = { confirmDeleteOrphans(orphans) },
                    onSecondary = {},
                ),
            )
        }

        addUnassignedRows(ModelPurpose.CHAT)
        add(Row.Custom(onAdd = { addCustomRepo(ModelPurpose.CHAT) }))
    }

    private fun translationRows(device: DeviceProfile): List<Row> = buildList {
        if (!LlamaBridge.isAvailable) add(Row.Header(getString(R.string.model_native_missing)))
        addHiddenModelsRow()

        val hidden = container.settings.hiddenModelIds

        // MADLAD-400 first: the flagship pick — an actual translation model
        // covering hundreds of languages, not a chat model prompted for the
        // task (see TranslationModels' own doc comment on why this is a
        // separate catalog) — and the one TranslationActivity offers to
        // download itself when nothing is installed yet, so it belongs
        // where that offer points: the top of this list, not buried under
        // AICore and the general chat models.
        add(Row.Header(getString(R.string.models_translation_specialized_header)))
        add(Row.Note(getString(R.string.models_translation_specialized_note)))
        // MADLAD stays flagship-first among equals (sortedByDescending is
        // stable) — this only ever promotes a specific quant that happens to
        // already be resident right now, never reorders the curated
        // size progression otherwise.
        TranslationModels.SEEDS.filter { it.id !in hidden }
            .sortedByDescending { container.isModelResident(it.id) }
            .forEach { seed -> add(translationModelRow(seed, device)) }
        // The user's own translation models, added on this tab.
        container.customSeeds(ModelPurpose.TRANSLATION).forEach { seed -> add(translationModelRow(seed, device)) }

        add(Row.Note(getString(R.string.models_translation_note)))
        add(Row.Header(getString(R.string.models_local_header)))
        LocalModels.SEEDS.filter { it.id !in hidden }
            .sortedWith(
                compareByDescending<LocalModelSeed> { container.isModelResident(it.id) }
                    .thenByDescending { device.fitsBudget(it.approxSizeBytes) },
            ).forEach { seed -> add(translationModelRow(seed, device)) }

        addUnassignedRows(ModelPurpose.TRANSLATION)
        add(Row.Custom(onAdd = { addCustomRepo(ModelPurpose.TRANSLATION) }))
    }

    /**
     * Custom models with no recorded purpose ([AppContainer.unassignedCustomSeeds]),
     * shown on both tabs with "Use here" — the user says where each one
     * belongs instead of the app guessing from a repo name.
     */
    private fun MutableList<Row>.addUnassignedRows(purpose: ModelPurpose) {
        val unassigned = container.unassignedCustomSeeds()
        if (unassigned.isEmpty()) return
        add(Row.Header(getString(R.string.models_unassigned_header)))
        add(Row.Note(getString(R.string.models_unassigned_note)))
        unassigned.forEach { seed ->
            val state = container.downloads.stateOf(seed)
            add(
                Row.Model(
                    title = seed.title,
                    subtitle = seed.repoIds.first(),
                    selected = false,
                    status = textStatus(state, container.modelStore.installedSize(seed)),
                    progress = null,
                    indeterminate = false,
                    primaryLabel = getString(R.string.model_assign_here),
                    primaryEnabled = true,
                    secondaryLabel = getString(R.string.model_remove),
                    onPrimary = {
                        container.assignCustomModel(seed.repoIds.first(), purpose)
                        render()
                    },
                    onSecondary = { removeCustomSeed(seed, null) },
                ),
            )
        }
    }

    private fun translationModelRow(seed: LocalModelSeed, device: DeviceProfile): Row.Model {
        val state = container.downloads.stateOf(seed)
        val fitsBudget = device.fitsBudget(seed.approxSizeBytes)
        // Same reasoning as textRows' own selected — see its comment.
        val selected = container.settings.translationModel == seed.id && fitsBudget
        // Same reasoning as textRows' own isLoadedNow — see its comment.
        val isLoadedNow = container.isModelResident(seed.id)

        return Row.Model(
            title = seed.title,
            subtitle = buildString {
                append(seed.paramsLabel)
                if (seed.approxSizeBytes > 0) append(" · ~${size(seed.approxSizeBytes)}")
                append(" · ").append(fitLabel(device.classifyFit(seed.approxSizeBytes.takeIf { it > 0 } ?: 1)))
                if (!fitsBudget) append(" · ").append(getString(R.string.model_exceeds_ram_budget))
                if (isLoadedNow) append(" · ").append(getString(R.string.model_loaded_now))
                append("\n").append(seed.resolvedNote(this@ModelsActivity))
            },
            selected = selected,
            status = textStatus(state, container.modelStore.installedSize(seed)),
            progress = downloadProgress(state, seed),
            indeterminate = state is DownloadState.Resolving,
            primaryLabel = when (state) {
                is DownloadState.Installed ->
                    getString(if (selected) R.string.model_installed else R.string.model_use)
                is DownloadState.Running -> getString(R.string.model_pause)
                is DownloadState.Paused -> getString(R.string.model_resume)
                is DownloadState.Resolving -> getString(R.string.model_cancel)
                is DownloadState.Failed -> getString(R.string.model_retry)
                DownloadState.Idle -> getString(R.string.model_download)
            },
            primaryEnabled = !(state is DownloadState.Installed && selected),
            secondaryLabel = if (seed.isCustom) {
                getString(R.string.model_remove)
            } else {
                when (state) {
                    is DownloadState.Failed -> getString(R.string.model_details)
                    is DownloadState.Installed -> getString(R.string.model_delete)
                    is DownloadState.Paused -> getString(R.string.model_delete)
                    DownloadState.Idle -> getString(R.string.model_hide)
                    else -> null
                }
            },
            onPrimary = { onTranslationPrimary(seed) },
            onSecondary = { onTranslationSecondary(seed) },
            warnsOverBudget = !fitsBudget,
            loadedNow = isLoadedNow,
        )
    }

    // ── Embedding model ────────────────────────────────────────────────────

    /**
     * [ExperimentalEmbeddingModels.E5_BASE] only — not E5_SMALL, which never
     * loads at all (see its own doc comment) and stays reachable purely as
     * an experimental candidate under Settings → Advanced, not here. This
     * is the app's one production semantic-memory model, shown with the
     * two things "installed" conflates for a chat model but genuinely don't
     * for this one: whether the GGUF is on disk, and whether it's actually
     * the loaded embedder memory retrieval is using right now (see
     * [AppContainer.semanticEmbedderReady] — a downloaded-but-not-yet-loaded
     * or downloaded-but-disabled-in-Memory-settings model is a real,
     * distinct state, not a rounding error).
     */
    private fun embeddingRows(): List<Row> = buildList {
        add(Row.Header(getString(R.string.models_embedding_header)))

        val spec = ExperimentalEmbeddingModels.E5_BASE
        val state = container.experimentalEmbeddingDownloads.stateOf(spec)
        val installed = state is ExperimentalDownloadState.Installed

        add(
            Row.Model(
                title = spec.title,
                subtitle = getString(R.string.models_embedding_subtitle, spec.dimension),
                selected = false,
                status = embeddingStatus(state),
                progress = (state as? ExperimentalDownloadState.Running)?.progress?.fraction,
                indeterminate = state is ExperimentalDownloadState.Resolving,
                primaryLabel = when (state) {
                    is ExperimentalDownloadState.Installed -> getString(R.string.model_state_installed)
                    is ExperimentalDownloadState.Running -> getString(R.string.model_pause)
                    ExperimentalDownloadState.Resolving -> getString(R.string.model_cancel)
                    is ExperimentalDownloadState.Failed -> getString(R.string.model_retry)
                    ExperimentalDownloadState.Idle -> getString(R.string.model_download)
                },
                primaryEnabled = !installed,
                secondaryLabel = when (state) {
                    is ExperimentalDownloadState.Failed -> getString(R.string.model_details)
                    is ExperimentalDownloadState.Installed -> getString(R.string.model_delete)
                    else -> null
                },
                onPrimary = { onEmbeddingPrimary(spec) },
                onSecondary = { onEmbeddingSecondary(spec) },
            ),
        )
    }

    /**
     * Same three-state distinction as [MemoryActivity.renderModelStatus] —
     * installed-but-not-ready means something different depending on *why*:
     * off elsewhere in Settings vs. merely unloaded under memory pressure
     * and about to reload on its own. This used to always say "enable
     * Semantic retrieval in Memory" whenever [AppContainer.semanticEmbedderReady]
     * was false, which was actively wrong the moment that toggle was
     * already on and the model was just between a pressure-unload and its
     * automatic reload — exactly what a real screenshot showed: this
     * screen telling the user to enable a setting that Memory's own screen,
     * at the same moment, showed already enabled.
     */
    private fun embeddingStatus(state: ExperimentalDownloadState): String = when (state) {
        is ExperimentalDownloadState.Installed -> {
            val statusLine = when {
                !container.settings.memoryEnabled -> getString(R.string.embed_status_memory_off)
                !container.settings.semanticMemoryEnabled -> getString(R.string.embed_status_semantic_off)
                container.semanticEmbedderReady -> getString(R.string.embed_status_loaded)
                else -> getString(R.string.embed_status_unloaded)
            }
            "${getString(R.string.model_state_installed)} · ${size(container.experimentalEmbeddingStore.installedSize(ExperimentalEmbeddingModels.E5_BASE))}\n$statusLine"
        }
        is ExperimentalDownloadState.Resolving -> getString(R.string.model_state_resolving, ExperimentalEmbeddingModels.E5_BASE.repoId)
        is ExperimentalDownloadState.Running ->
            getString(
                R.string.models_embedding_downloading,
                size(state.progress.bytesDownloaded),
                if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?",
            )
        is ExperimentalDownloadState.Failed -> getString(R.string.model_state_error, state.message.lineSequence().first())
        ExperimentalDownloadState.Idle -> getString(R.string.models_embedding_not_downloaded)
    }

    private fun onEmbeddingPrimary(spec: EmbeddingModelSpec) {
        when (container.experimentalEmbeddingDownloads.stateOf(spec)) {
            is ExperimentalDownloadState.Running, ExperimentalDownloadState.Resolving -> container.experimentalEmbeddingDownloads.cancel(spec)
            is ExperimentalDownloadState.Installed -> {}
            else -> NetworkPolicy.confirmIfNeeded(this, container.settings) { container.experimentalEmbeddingDownloads.start(spec) }
        }
    }

    private fun onEmbeddingSecondary(spec: EmbeddingModelSpec) {
        when (val state = container.experimentalEmbeddingDownloads.stateOf(spec)) {
            is ExperimentalDownloadState.Failed -> showDetails(spec.title, state.message)
            else -> {
                container.experimentalEmbeddingDownloads.delete(spec)
                render()
            }
        }
    }

    /**
     * Names every file before deleting anything — these are large, and a
     * generic file dating from before this exact build's catalog is exactly
     * the kind of thing worth a moment's confirmation, not a silent bulk
     * delete on a mis-tap.
     */
    private fun confirmDeleteOrphans(orphans: List<java.io.File>) {
        val totalBytes = orphans.sumOf { it.length() }
        val listing = orphans.joinToString("\n") { "• ${it.name} (${size(it.length())})" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.models_orphans_delete, size(totalBytes)))
            .setMessage(listing)
            .setPositiveButton(R.string.model_delete) { _, _ ->
                orphans.forEach { it.delete() }
                render()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun whisperModelRow(seed: WhisperModelSeed, device: DeviceProfile, selected: Boolean): Row.Model {
        val state = container.whisperDownloads.stateOf(seed)
        return Row.Model(
            title = seed.title,
            subtitle = if (seed.approxSizeBytes > 0) {
                "~${size(seed.approxSizeBytes)} · ${fitLabel(device.classifyFit(seed.approxSizeBytes))}"
            } else {
                seed.modelUrl
            },
            selected = selected,
            status = when {
                state is WhisperDownloadState.Installed -> getString(R.string.model_state_installed)
                state is WhisperDownloadState.Running ->
                    getString(
                        R.string.download_progress_label,
                        state.stage,
                        size(state.progress.bytesDownloaded),
                        if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?",
                    )
                state is WhisperDownloadState.Failed ->
                    getString(R.string.model_state_error, state.message.lineSequence().first())
                else -> null
            },
            progress = (state as? WhisperDownloadState.Running)?.progress?.fraction,
            indeterminate = false,
            primaryLabel = when (state) {
                is WhisperDownloadState.Installed ->
                    getString(if (selected) R.string.model_installed else R.string.model_use)
                is WhisperDownloadState.Running -> getString(R.string.model_pause)
                is WhisperDownloadState.Failed -> getString(R.string.model_retry)
                WhisperDownloadState.Idle -> getString(R.string.model_download)
            },
            primaryEnabled = !(state is WhisperDownloadState.Installed && selected),
            secondaryLabel = if (seed.isCustom) {
                getString(R.string.model_remove)
            } else {
                when (state) {
                    is WhisperDownloadState.Failed -> getString(R.string.model_details)
                    is WhisperDownloadState.Installed -> getString(R.string.model_delete)
                    else -> null
                }
            },
            onPrimary = { onVoicePrimary(seed) },
            onSecondary = { onVoiceSecondary(seed) },
        )
    }

    private fun voiceRows(device: DeviceProfile): List<Row> = buildList {
        add(Row.Header(getString(R.string.models_voice_header)))
        val selectedSeed = container.installedWhisperSeed(container.settings.whisperModelId)
        // Real device report: this used to check only whisperModelId, so a
        // Vosk pick below could show as "selected" here too — both engines
        // remember their own last pick (see Settings.activeSttEngine's own
        // doc comment), but only one is ever actually in use. "Selected"
        // here means *that*, not merely "this is the id stored for Whisper".
        val whisperActive = container.settings.activeSttEngine == AsrEngineType.WHISPER

        (WhisperModels.SEEDS + container.customWhisperSeeds()).forEach { seed ->
            add(whisperModelRow(seed, device, whisperActive && selectedSeed?.id == seed.id))
        }
        add(Row.Note(getString(R.string.settings_whisper_note)))
        add(
            Row.Custom(
                title = R.string.models_custom_voice_title,
                hint = R.string.models_custom_voice_hint,
                onAdd = { addCustomWhisperModel() },
            ),
        )

        // Vosk ASR spike (docs/14-vosk-spike.md): a second catalogue on the
        // same tab, not a separate screen — this is exactly where someone
        // choosing "which voice model" already looks, and the whole point
        // of the spike is trying it against Whisper with as little new UI
        // as possible.
        add(Row.Header(getString(R.string.models_vosk_header)))
        val selectedVoskSeed = VoskModelStore.installedSeed(this@ModelsActivity, container.settings.voskModelId)
        val voskActive = container.settings.activeSttEngine == AsrEngineType.VOSK

        VoskModels.SEEDS.forEach { seed ->
            val state = container.voskDownloads.stateOf(seed)
            val selected = voskActive && selectedVoskSeed?.id == seed.id

            add(
                Row.Model(
                    title = seed.title,
                    subtitle = "~${size(seed.approxSizeBytes)} · ${fitLabel(device.classifyFit(seed.approxSizeBytes))}",
                    selected = selected,
                    status = when {
                        state is VoskDownloadState.Installed -> getString(R.string.model_state_installed)
                        state is VoskDownloadState.Running ->
                            getString(
                                R.string.download_progress_label,
                                state.stage,
                                size(state.progress.bytesDownloaded),
                                if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?",
                            )
                        state is VoskDownloadState.Failed ->
                            getString(R.string.model_state_error, state.message.lineSequence().first())
                        else -> null
                    },
                    progress = (state as? VoskDownloadState.Running)?.progress?.fraction,
                    indeterminate = false,
                    primaryLabel = when (state) {
                        is VoskDownloadState.Installed ->
                            getString(if (selected) R.string.model_installed else R.string.model_use)
                        is VoskDownloadState.Running -> getString(R.string.model_pause)
                        is VoskDownloadState.Failed -> getString(R.string.model_retry)
                        VoskDownloadState.Idle -> getString(R.string.model_download)
                    },
                    primaryEnabled = !(state is VoskDownloadState.Installed && selected),
                    secondaryLabel = when (state) {
                        is VoskDownloadState.Failed -> getString(R.string.model_details)
                        is VoskDownloadState.Installed -> getString(R.string.model_delete)
                        else -> null
                    },
                    onPrimary = { onVoskPrimary(seed) },
                    onSecondary = { onVoskSecondary(seed) },
                ),
            )
        }
        add(Row.Note(getString(R.string.models_vosk_note)))
    }

    private fun textStatus(state: DownloadState, installedBytes: Long): String? = when (state) {
        is DownloadState.Installed -> getString(R.string.model_state_installed) + " · ${size(installedBytes)}"
        is DownloadState.Paused -> getString(R.string.model_state_paused, size(state.partialBytes))
        is DownloadState.Resolving -> getString(R.string.model_state_resolving, state.repoId)
        is DownloadState.Running ->
            getString(
                R.string.download_progress_label,
                state.source,
                size(state.progress.bytesDownloaded),
                if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?",
            )
        is DownloadState.Failed -> getString(R.string.model_state_error, state.message.lineSequence().first())
        DownloadState.Idle -> null
    }

    /**
     * [DownloadState.Running]'s own progress, or — for [DownloadState.Paused]
     * — [LocalModelSeed.approxSizeBytes] standing in for the real total
     * (not re-resolved until the download actually restarts), same
     * approximation [fitLabel]/[fitsBudget] already use for this seed
     * elsewhere on this screen.
     */
    private fun downloadProgress(state: DownloadState, seed: LocalModelSeed): Float? = when (state) {
        is DownloadState.Running -> state.progress.fraction
        is DownloadState.Paused ->
            seed.approxSizeBytes.takeIf { it > 0 }?.let { (state.partialBytes.toFloat() / it).coerceIn(0f, 1f) }
        else -> null
    }

    /**
     * A direct download link, not a repo id — see [WhisperModels.custom]'s
     * own doc comment for why whisper.cpp models can't reuse
     * [normalizeRepoInput]/[HuggingFaceResolver]'s repo resolution.
     */
    private fun addCustomWhisperModel() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.models_custom_voice_input_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.models_custom_voice_title)
            .setMessage(R.string.models_custom_voice_hint)
            .setView(input)
            .setPositiveButton(R.string.model_download) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    val seed = container.addCustomWhisperModel(url)
                    NetworkPolicy.confirmIfNeeded(this, container.settings) { container.whisperDownloads.start(seed) }
                    render()
                } else {
                    Toast.makeText(this, R.string.models_custom_bad_format, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun addCustomRepo(purpose: ModelPurpose) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.models_custom_input_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.models_custom_title)
            .setMessage(R.string.models_custom_hint)
            .setView(input)
            .setPositiveButton(R.string.model_download) { _, _ ->
                val repo = normalizeRepoInput(input.text?.toString().orEmpty())
                if (repo != null) {
                    val seed = container.addCustomModel(repo, purpose)
                    NetworkPolicy.confirmIfNeeded(this, container.settings) { container.downloads.start(seed) }
                    render()
                } else {
                    Toast.makeText(this, R.string.models_custom_bad_format, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Accepts `owner/repo` as documented, but also whatever pasting a repo
     * page's own address bar actually produces — real device report: a full
     * `https://huggingface.co/owner/repo` link passed the old bare
     * `contains('/')` check and was used as the repo id verbatim, which
     * [HuggingFaceResolver] can't resolve (it builds its own API URL out of
     * `owner/repo`) — the download failed with no visible error the user
     * could connect back to what they'd typed, since [ModelDownloads] didn't
     * log anything either (see its own `log` parameter, added alongside
     * this). Strips a `https://`/`http://` scheme and a `huggingface.co/`
     * host, then keeps only the first two remaining path segments — so a
     * link to one specific file (`.../blob/main/model.gguf`) or tree
     * (`.../tree/main`) still resolves to the repo itself, not a path
     * [HuggingFaceResolver] would treat as a nonexistent repo. Null means
     * still not enough there to be a repo id.
     */
    private fun normalizeRepoInput(raw: String): String? {
        val stripped = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("huggingface.co/")
            .trim('/')
        val segments = stripped.split('/').filter { it.isNotBlank() }
        return if (segments.size >= 2) "${segments[0]}/${segments[1]}" else null
    }

    private fun describeDevice(device: DeviceProfile) = buildString {
        append(getString(R.string.device_ram_line, gb(device.totalRamBytes), gb(device.availableRamBytes))).append("\n")
        append(getString(R.string.device_budget_line, gb(device.usableRamBytes), container.settings.ramBudgetPercent)).append("\n")
        append(getString(R.string.device_cores_line, device.cpuCores, gb(device.availableStorageBytes)))
    }

    private fun gb(bytes: Long): String =
        if (bytes >= 1_000_000_000) getString(R.string.unit_gb, "%.1f".format(bytes / 1_000_000_000.0))
        else getString(R.string.unit_mb, "%.0f".format(bytes / 1_000_000.0))

    private fun size(bytes: Long): String =
        if (bytes >= 1_000_000_000) getString(R.string.unit_gb, "%.2f".format(bytes / 1_000_000_000.0))
        else getString(R.string.unit_mb, "%.0f".format(bytes / 1_000_000.0))

    private fun fitLabel(fit: ModelFit): String = getString(
        when (fit) {
            ModelFit.LIGHTWEIGHT -> R.string.model_fit_light
            ModelFit.RECOMMENDED -> R.string.model_fit_recommended
            ModelFit.ADVANCED -> R.string.model_fit_heavy
            ModelFit.TOO_LARGE -> R.string.model_fit_too_large
        },
    )

    /**
     * One row shape for both categories: the screen differs in what it lists,
     * not in how a listing behaves, so the holder takes already-resolved
     * labels and callbacks rather than branching on model type itself.
     */
    sealed interface Row {
        data class Header(val title: String) : Row
        data class Note(val text: String) : Row
        data class Custom(val title: Int = R.string.models_custom_title, val hint: Int = R.string.models_custom_hint, val onAdd: () -> Unit) : Row

        data class Model(
            val title: String,
            val subtitle: String,
            val selected: Boolean,
            val status: String?,
            val progress: Float?,
            val indeterminate: Boolean,
            val primaryLabel: String,
            val primaryEnabled: Boolean,
            val secondaryLabel: String?,
            val onPrimary: () -> Unit,
            val onSecondary: () -> Unit,
            /** Whether this size fails [DeviceProfile.fitsBudget] — see [ModelHolder.bind]. */
            val warnsOverBudget: Boolean = false,
            /** Whether [AppContainer.isModelResident] says this model is actually in RAM right now — see [ModelHolder.bind]. */
            val loadedNow: Boolean = false,
        ) : Row
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            // A download's progress ticks land here via render() many times a
            // second (see ModelDownloader). notifyDataSetChanged() rebinds
            // every row on each one, which is enough main-thread churn that a
            // tap on a *different* row's download/delete button can miss its
            // target while its own ViewHolder is being torn down underneath
            // the finger. Diffing keeps only the row that actually changed
            // (the one downloading) getting rebound.
            val previous = rows
            rows = next
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = previous.size
                override fun getNewListSize() = next.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int) = rowIdentity(previous[oldPos]) == rowIdentity(next[newPos])
                override fun areContentsTheSame(oldPos: Int, newPos: Int) = rowContent(previous[oldPos]) == rowContent(next[newPos])
            }).dispatchUpdatesTo(this)
        }

        private fun rowIdentity(row: Row): Any = when (row) {
            is Row.Header -> "header:${row.title}"
            is Row.Note -> "note:${row.text}"
            is Row.Custom -> "custom:${row.title}"
            is Row.Model -> "model:${row.title}"
        }

        // Excludes onPrimary/onSecondary: those are freshly-allocated lambdas
        // on every render() call, so comparing them would always report a
        // change and defeat the diff entirely.
        private fun rowContent(row: Row): Any = when (row) {
            is Row.Model -> listOf(
                row.title, row.subtitle, row.selected, row.status, row.progress,
                row.indeterminate, row.primaryLabel, row.primaryEnabled, row.secondaryLabel,
                row.warnsOverBudget, row.loadedNow,
            )
            else -> row
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header, is Row.Note -> TYPE_HEADER
            is Row.Model, is Row.Custom -> TYPE_MODEL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_MODEL) {
                ModelHolder(ItemLocalModelBinding.inflate(inflater, parent, false))
            } else {
                HeaderHolder(ItemModelBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.title, bold = true)
                is Row.Note -> (holder as HeaderHolder).bind(row.text, bold = false)
                is Row.Model -> (holder as ModelHolder).bind(row)
                is Row.Custom -> (holder as ModelHolder).bindCustom(row.title, row.hint, row.onAdd)
            }
        }
    }

    private class HeaderHolder(val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String, bold: Boolean) {
            binding.modelName.text = title
            binding.modelName.setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            binding.modelName.textSize = if (bold) 15f else 12f
            binding.modelSpecs.visibility = View.GONE
            binding.modelVerdict.visibility = View.GONE
        }
    }

    private class ModelHolder(val binding: ItemLocalModelBinding) : RecyclerView.ViewHolder(binding.root) {

        // Captured once, before any row ever recolors it — a recycled
        // ViewHolder must fall back to exactly this color for every row that
        // doesn't warn, or a red subtitle from whichever row last warned
        // would keep bleeding into an unrelated row reusing the same holder.
        private val defaultSubtitleColor = binding.localSubtitle.currentTextColor

        fun bindCustom(title: Int, hint: Int, onClick: () -> Unit) {
            val context = binding.root.context
            binding.localTitle.text = context.getString(title)
            binding.localSubtitle.text = context.getString(hint)
            binding.localSubtitle.setTextColor(defaultSubtitleColor)
            binding.localStatus.visibility = View.GONE
            binding.localProgress.visibility = View.GONE
            binding.localSecondaryButton.visibility = View.GONE
            binding.localPrimaryButton.text = context.getString(R.string.models_custom_add)
            binding.localPrimaryButton.isEnabled = true
            binding.localPrimaryButton.setOnClickListener { onClick() }
        }

        fun bind(row: Row.Model) {
            binding.localTitle.text = if (row.selected) "${row.title}  ✓" else row.title
            binding.localSubtitle.text = row.subtitle
            // A plain text suffix ("exceeds RAM budget") read as just another
            // detail among several, easy to miss right next to a fit label
            // that used to say "Recommended" for the same model (see
            // DeviceProfile.classifyFit's own doc comment on that
            // contradiction). Coloring the whole line makes a model that
            // will refuse to load visually distinct at a glance, not just a
            // few extra words in the middle of the same run of text.
            //
            // Real device report: the ✓ mark was read as "this is using RAM
            // right now" — it only ever meant "this is Settings' current
            // pick" (see isLoadedNow's own comment at its call sites). This
            // is the actual "using RAM right now" signal, colored distinctly
            // from the ✓ so the two questions ("what's configured" vs
            // "what's actually loaded, this instant") don't collapse back
            // into looking like the same thing again.
            binding.localSubtitle.setTextColor(
                when {
                    row.loadedNow -> com.google.android.material.color.MaterialColors.getColor(
                        binding.root, com.google.android.material.R.attr.colorPrimary, defaultSubtitleColor,
                    )
                    row.warnsOverBudget -> com.google.android.material.color.MaterialColors.getColor(
                        binding.root, com.google.android.material.R.attr.colorError, defaultSubtitleColor,
                    )
                    else -> defaultSubtitleColor
                },
            )

            binding.localStatus.text = row.status.orEmpty()
            binding.localStatus.visibility = if (row.status.isNullOrBlank()) View.GONE else View.VISIBLE

            binding.localProgress.visibility = if (row.progress != null || row.indeterminate) View.VISIBLE else View.GONE
            binding.localProgress.isIndeterminate = row.indeterminate
            row.progress?.let { binding.localProgress.progress = (it * 100).toInt() }

            binding.localPrimaryButton.text = row.primaryLabel
            binding.localPrimaryButton.isEnabled = row.primaryEnabled
            binding.localPrimaryButton.setOnClickListener { row.onPrimary() }

            binding.localSecondaryButton.visibility = if (row.secondaryLabel == null) View.GONE else View.VISIBLE
            binding.localSecondaryButton.text = row.secondaryLabel.orEmpty()
            binding.localSecondaryButton.setOnClickListener { row.onSecondary() }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MODEL = 1

        /** [Category.name], read by [onCreate] to open on a specific tab — see [intent]. */
        const val EXTRA_CATEGORY = "category"

        fun intent(context: android.content.Context, category: Category): android.content.Intent =
            android.content.Intent(context, ModelsActivity::class.java).putExtra(EXTRA_CATEGORY, category.name)
    }
}
