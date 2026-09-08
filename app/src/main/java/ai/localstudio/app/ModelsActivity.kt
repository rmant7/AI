package ai.localstudio.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
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
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.models.DownloadState
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.whisper.WhisperDownloadState
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.ModelFit
import kotlinx.coroutines.launch

/**
 * Every model the app can run, grouped by what it is *for* — chat models and
 * voice models are one catalog under a task switcher, the way Edge Gallery
 * organises its own. Voice models used to be buried in Settings, which made
 * them feel like a different kind of thing than the chat models they sit
 * beside conceptually.
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

    enum class Category { TEXT, VOICE }

    private lateinit var binding: ActivityModelsBinding
    private lateinit var container: AppContainer
    private val adapter = RowAdapter()
    private val customSeeds = mutableListOf<LocalModelSeed>()
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

        binding.categoryToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            category = if (checkedId == R.id.categoryVoice) Category.VOICE else Category.TEXT
            render()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch { container.downloads.state.collect { render() } }
        lifecycleScope.launch { container.whisperDownloads.state.collect { render() } }
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

    // ── Text models ────────────────────────────────────────────────────────

    private fun onTextPrimary(seed: LocalModelSeed) {
        when (container.downloads.stateOf(seed)) {
            is DownloadState.Installed -> useLocally(seed)
            is DownloadState.Running, is DownloadState.Resolving -> container.downloads.cancel(seed)
            else -> container.downloads.start(seed)
        }
    }

    private fun onTextSecondary(seed: LocalModelSeed) {
        when (val state = container.downloads.stateOf(seed)) {
            is DownloadState.Failed -> showDetails(seed.title, state.message)
            else -> {
                container.downloads.delete(seed)
                render()
            }
        }
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
        if (!fitsRamBudget(seed, container.device)) {
            AlertDialog.Builder(this)
                .setTitle(seed.title)
                .setMessage(R.string.model_ram_warning)
                .setPositiveButton(R.string.model_ram_warning_continue) { _, _ -> switchToLocal(seed) }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
            return
        }
        switchToLocal(seed)
    }

    private fun switchToLocal(seed: LocalModelSeed) {
        container.settings.providerId = CloudProviders.LOCAL.id
        container.settings.chatModel = seed.id
        Toast.makeText(this, getString(R.string.models_switched_chat, seed.title), Toast.LENGTH_SHORT).show()
        render()
    }

    private fun fitsRamBudget(seed: LocalModelSeed, device: DeviceProfile): Boolean =
        seed.approxSizeBytes == 0L || seed.approxSizeBytes * 13 / 10 <= device.usableRamBytes

    // ── Voice models ───────────────────────────────────────────────────────

    private fun onVoicePrimary(seed: WhisperModelSeed) {
        when (container.whisperDownloads.stateOf(seed)) {
            is WhisperDownloadState.Installed -> useForVoice(seed)
            is WhisperDownloadState.Running -> container.whisperDownloads.cancel(seed)
            else -> container.whisperDownloads.start(seed)
        }
    }

    private fun onVoiceSecondary(seed: WhisperModelSeed) {
        when (val state = container.whisperDownloads.stateOf(seed)) {
            is WhisperDownloadState.Failed -> showDetails(seed.title, state.message)
            else -> {
                container.whisperDownloads.delete(seed)
                render()
            }
        }
    }

    private fun useForVoice(seed: WhisperModelSeed) {
        container.settings.whisperModelId = seed.id
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
        adapter.submit(if (category == Category.VOICE) voiceRows(device) else textRows(device))
    }

    private fun textRows(device: DeviceProfile): List<Row> = buildList {
        if (!LlamaBridge.isAvailable) add(Row.Header(getString(R.string.model_native_missing)))
        add(Row.Header(getString(R.string.models_local_header)))

        val freshness = container.catalogFreshness.cached()

        (LocalModels.SEEDS + customSeeds).forEach { seed ->
            val state = container.downloads.stateOf(seed)
            val selected = container.settings.providerId == CloudProviders.LOCAL.id &&
                container.settings.chatModel == seed.id
            val fitsBudget = fitsRamBudget(seed, device)
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
                        if (!fitsBudget) append(" · превышает бюджет памяти")
                        if (knownStale) append(" · ").append(getString(R.string.model_catalog_stale))
                        append("\n").append(seed.note)
                    },
                    selected = selected,
                    status = textStatus(state, container.modelStore.installedSize(seed)),
                    progress = (state as? DownloadState.Running)?.progress?.fraction,
                    indeterminate = state is DownloadState.Resolving,
                    primaryLabel = when (state) {
                        is DownloadState.Installed ->
                            getString(if (selected) R.string.model_installed else R.string.model_use)
                        is DownloadState.Running, is DownloadState.Resolving -> getString(R.string.model_cancel)
                        is DownloadState.Failed -> getString(R.string.model_retry)
                        DownloadState.Idle -> getString(R.string.model_download)
                    },
                    primaryEnabled = !(state is DownloadState.Installed && selected),
                    secondaryLabel = when (state) {
                        is DownloadState.Failed -> getString(R.string.model_details)
                        is DownloadState.Installed -> getString(R.string.model_delete)
                        else -> null
                    },
                    onPrimary = { onTextPrimary(seed) },
                    onSecondary = { onTextSecondary(seed) },
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
        val orphans = container.modelStore.orphanedFiles(LocalModels.SEEDS + customSeeds)
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

        add(Row.Custom)
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

    private fun voiceRows(device: DeviceProfile): List<Row> = buildList {
        add(Row.Header(getString(R.string.models_voice_header)))
        val selectedSeed = container.whisperStore.installedSeed(container.settings.whisperModelId)
        val anyRunning = container.whisperDownloads.state.value.values
            .any { it is WhisperDownloadState.Running }

        WhisperModels.SEEDS.forEach { seed ->
            val state = container.whisperDownloads.stateOf(seed)
            val selected = selectedSeed?.id == seed.id
            val blocked = anyRunning && state is WhisperDownloadState.Idle

            add(
                Row.Model(
                    title = seed.title,
                    subtitle = "~${size(seed.approxSizeBytes)} · ${fitLabel(device.classifyFit(seed.approxSizeBytes))}",
                    selected = selected,
                    status = when {
                        state is WhisperDownloadState.Installed -> getString(R.string.model_state_installed)
                        state is WhisperDownloadState.Running ->
                            "${state.stage}: ${size(state.progress.bytesDownloaded)} из " +
                                (if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?")
                        state is WhisperDownloadState.Failed ->
                            getString(R.string.model_state_error, state.message.lineSequence().first())
                        blocked -> getString(R.string.model_state_wait_other)
                        else -> null
                    },
                    progress = (state as? WhisperDownloadState.Running)?.progress?.fraction,
                    indeterminate = false,
                    primaryLabel = when (state) {
                        is WhisperDownloadState.Installed ->
                            getString(if (selected) R.string.model_installed else R.string.model_use)
                        is WhisperDownloadState.Running -> getString(R.string.model_cancel)
                        is WhisperDownloadState.Failed -> getString(R.string.model_retry)
                        WhisperDownloadState.Idle -> getString(R.string.model_download)
                    },
                    primaryEnabled = !(state is WhisperDownloadState.Installed && selected) && !blocked,
                    secondaryLabel = when (state) {
                        is WhisperDownloadState.Failed -> getString(R.string.model_details)
                        is WhisperDownloadState.Installed -> getString(R.string.model_delete)
                        else -> null
                    },
                    onPrimary = { onVoicePrimary(seed) },
                    onSecondary = { onVoiceSecondary(seed) },
                ),
            )
        }
        add(Row.Note(getString(R.string.settings_whisper_note)))
    }

    private fun textStatus(state: DownloadState, installedBytes: Long): String? = when (state) {
        is DownloadState.Installed -> getString(R.string.model_state_installed) + " · ${size(installedBytes)}"
        is DownloadState.Resolving -> getString(R.string.model_state_resolving, state.repoId)
        is DownloadState.Running ->
            "${state.source}: ${size(state.progress.bytesDownloaded)} из " +
                (if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?")
        is DownloadState.Failed -> getString(R.string.model_state_error, state.message.lineSequence().first())
        DownloadState.Idle -> null
    }

    private fun addCustomRepo() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.models_custom_input_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.models_custom_title)
            .setMessage(R.string.models_custom_hint)
            .setView(input)
            .setPositiveButton(R.string.model_download) { _, _ ->
                val repo = input.text?.toString()?.trim().orEmpty()
                if (repo.contains('/')) {
                    val seed = LocalModels.custom(repo)
                    if (customSeeds.none { it.id == seed.id }) customSeeds += seed
                    container.downloads.start(seed)
                    render()
                } else {
                    Toast.makeText(this, R.string.models_custom_bad_format, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun describeDevice(device: DeviceProfile) = buildString {
        append("RAM: ${gb(device.totalRamBytes)} всего, ${gb(device.availableRamBytes)} свободно\n")
        append("Бюджет на модель: ${gb(device.usableRamBytes)} (${container.settings.ramBudgetPercent}% RAM)\n")
        append("Ядер: ${device.cpuCores} · свободно на диске: ${gb(device.availableStorageBytes)}")
    }

    private fun gb(bytes: Long): String =
        if (bytes >= 1_000_000_000) "%.1f ГБ".format(bytes / 1_000_000_000.0)
        else "%.0f МБ".format(bytes / 1_000_000.0)

    private fun size(bytes: Long): String =
        if (bytes >= 1_000_000_000) "%.2f ГБ".format(bytes / 1_000_000_000.0)
        else "%.0f МБ".format(bytes / 1_000_000.0)

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
        data object Custom : Row

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
            Row.Custom -> "custom"
            is Row.Model -> "model:${row.title}"
        }

        // Excludes onPrimary/onSecondary: those are freshly-allocated lambdas
        // on every render() call, so comparing them would always report a
        // change and defeat the diff entirely.
        private fun rowContent(row: Row): Any = when (row) {
            is Row.Model -> listOf(
                row.title, row.subtitle, row.selected, row.status, row.progress,
                row.indeterminate, row.primaryLabel, row.primaryEnabled, row.secondaryLabel,
            )
            else -> row
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header, is Row.Note -> TYPE_HEADER
            is Row.Model, Row.Custom -> TYPE_MODEL
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
                Row.Custom -> (holder as ModelHolder).bindCustom { addCustomRepo() }
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

        fun bindCustom(onClick: () -> Unit) {
            val context = binding.root.context
            binding.localTitle.text = context.getString(R.string.models_custom_title)
            binding.localSubtitle.text = context.getString(R.string.models_custom_hint)
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

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MODEL = 1
    }
}
