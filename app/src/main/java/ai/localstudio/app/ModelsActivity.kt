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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityModelsBinding
import ai.localstudio.app.databinding.ItemLocalModelBinding
import ai.localstudio.app.databinding.ItemModelBinding
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.models.DownloadState
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.ModelFit
import kotlinx.coroutines.launch

/**
 * Every model here can be downloaded — including the ones that do not fit
 * comfortably.
 *
 * The fit label is advice, not a gate: it is the user's device, the user knows
 * what they run on it, and a list where the interesting entries are read-only
 * descriptions is a catalogue of things you cannot have. What the label does is
 * tell them what to expect before a multi-gigabyte download.
 */
class ModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelsBinding
    private lateinit var container: AppContainer
    private val adapter = RowAdapter(::onPrimary, ::onSecondary)
    private val customSeeds = mutableListOf<LocalModelSeed>()

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch { container.downloads.state.collect { render() } }
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

    private fun onPrimary(seed: LocalModelSeed) {
        when (container.downloads.stateOf(seed)) {
            is DownloadState.Installed -> useLocally(seed)
            is DownloadState.Running, is DownloadState.Resolving -> container.downloads.cancel(seed)
            else -> container.downloads.start(seed)
        }
    }

    private fun onSecondary(seed: LocalModelSeed) {
        when (val state = container.downloads.stateOf(seed)) {
            is DownloadState.Failed -> AlertDialog.Builder(this)
                .setTitle(seed.title)
                .setMessage(state.message)
                .setPositiveButton("Понятно", null)
                .show()

            else -> {
                container.downloads.delete(seed)
                render()
            }
        }
    }

    /** Switching the provider is what makes the downloaded model answer. */
    private fun useLocally(seed: LocalModelSeed) {
        container.settings.providerId = CloudProviders.LOCAL.id
        container.settings.chatModel = seed.id
        Toast.makeText(this, "Чат переключён на ${seed.title}", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        val device = container.device
        binding.deviceText.text = describeDevice(device)

        val rows = mutableListOf<Row>()
        if (!LlamaBridge.isAvailable) {
            rows += Row.Header(getString(R.string.model_native_missing))
        }
        rows += Row.Header(getString(R.string.models_local_header))

        (LocalModels.SEEDS + customSeeds).forEach { seed ->
            rows += Row.Local(
                seed = seed,
                state = container.downloads.stateOf(seed),
                installedBytes = container.modelStore.installedSize(seed),
                fit = device.classifyFit(seed.approxSizeBytes.takeIf { it > 0 } ?: 1),
                fitsBudget = seed.approxSizeBytes == 0L ||
                    seed.approxSizeBytes * 13 / 10 <= device.usableRamBytes,
                selected = container.settings.providerId == CloudProviders.LOCAL.id &&
                    container.settings.chatModel == seed.id,
            )
        }

        rows += Row.Custom
        adapter.submit(rows)
    }

    private fun addCustomRepo() {
        val input = android.widget.EditText(this).apply {
            hint = "owner/repo с GGUF-файлом"
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
                    Toast.makeText(this, "Нужен формат owner/repo", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun describeDevice(device: DeviceProfile) = buildString {
        append("RAM: ${gb(device.totalRamBytes)} всего, ${gb(device.availableRamBytes)} свободно\n")
        append("Бюджет на модель: ${gb(device.usableRamBytes)} (${container.settings.ramBudgetPercent}% RAM)\n")
        append("Ядер: ${device.cpuCores} · свободно на диске: ${gb(device.availableStorageBytes)}\n")
        append("Runtime: ${container.runtimeLabel}")
    }

    private fun gb(bytes: Long): String =
        if (bytes >= 1_000_000_000) "%.1f ГБ".format(bytes / 1_000_000_000.0)
        else "%.0f МБ".format(bytes / 1_000_000.0)

    sealed interface Row {
        data class Header(val title: String) : Row
        data class Local(
            val seed: LocalModelSeed,
            val state: DownloadState,
            val installedBytes: Long,
            val fit: ModelFit,
            val fitsBudget: Boolean,
            val selected: Boolean,
        ) : Row

        data object Custom : Row
    }

    private inner class RowAdapter(
        private val onPrimary: (LocalModelSeed) -> Unit,
        private val onSecondary: (LocalModelSeed) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Local -> TYPE_LOCAL
            Row.Custom -> TYPE_CUSTOM
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_LOCAL, TYPE_CUSTOM -> LocalHolder(ItemLocalModelBinding.inflate(inflater, parent, false))
                else -> HeaderHolder(ItemModelBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row.title)
                is Row.Local -> (holder as LocalHolder).bind(row, onPrimary, onSecondary)
                Row.Custom -> (holder as LocalHolder).bindCustom { addCustomRepo() }
            }
        }
    }

    private class HeaderHolder(val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.modelName.text = title
            binding.modelSpecs.visibility = View.GONE
            binding.modelVerdict.visibility = View.GONE
        }
    }

    private class LocalHolder(val binding: ItemLocalModelBinding) : RecyclerView.ViewHolder(binding.root) {

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

        fun bind(
            row: Row.Local,
            onPrimary: (LocalModelSeed) -> Unit,
            onSecondary: (LocalModelSeed) -> Unit,
        ) {
            val context = binding.root.context
            binding.localTitle.text = row.seed.title + if (row.selected) "  ✓" else ""
            binding.localSubtitle.text = buildString {
                append(row.seed.paramsLabel)
                if (row.seed.approxSizeBytes > 0) append(" · ~${size(row.seed.approxSizeBytes)}")
                append(" · ").append(fitLabel(row.fit))
                if (!row.fitsBudget) append(" · превышает бюджет памяти")
                append("\n").append(row.seed.note)
                append("\nисточники: ").append(row.seed.repoIds.joinToString(", "))
            }

            var progressVisible = false
            var secondaryVisible = false
            var secondaryText = context.getString(R.string.model_delete)
            var primaryText = context.getString(R.string.model_download)

            binding.localStatus.text = when (val state = row.state) {
                is DownloadState.Installed -> {
                    secondaryVisible = true
                    primaryText = context.getString(
                        if (row.selected) R.string.model_installed else R.string.model_use,
                    )
                    "Установлена · ${size(row.installedBytes)}"
                }

                is DownloadState.Resolving -> {
                    progressVisible = true
                    primaryText = context.getString(R.string.model_cancel)
                    "Ищу файл: ${state.repoId}…"
                }

                is DownloadState.Running -> {
                    progressVisible = true
                    primaryText = context.getString(R.string.model_cancel)
                    binding.localProgress.progress = (state.progress.fraction * 100).toInt()
                    val total = if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?"
                    "${state.source}: ${size(state.progress.bytesDownloaded)} из $total"
                }

                is DownloadState.Failed -> {
                    secondaryVisible = true
                    secondaryText = context.getString(R.string.model_details)
                    primaryText = context.getString(R.string.model_retry)
                    "Ошибка: " + state.message.lineSequence().first()
                }

                DownloadState.Idle -> ""
            }

            binding.localStatus.visibility =
                if (binding.localStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.localProgress.visibility = if (progressVisible) View.VISIBLE else View.GONE
            binding.localProgress.isIndeterminate = row.state is DownloadState.Resolving
            binding.localSecondaryButton.visibility = if (secondaryVisible) View.VISIBLE else View.GONE
            binding.localSecondaryButton.text = secondaryText
            binding.localPrimaryButton.text = primaryText
            binding.localPrimaryButton.isEnabled =
                !(row.state is DownloadState.Installed && row.selected)

            binding.localPrimaryButton.setOnClickListener { onPrimary(row.seed) }
            binding.localSecondaryButton.setOnClickListener { onSecondary(row.seed) }
        }

        private fun fitLabel(fit: ModelFit): String = when (fit) {
            ModelFit.LIGHTWEIGHT -> "лёгкая"
            ModelFit.RECOMMENDED -> "рекомендуется"
            ModelFit.ADVANCED -> "тяжёлая, но пойдёт"
            ModelFit.TOO_LARGE -> "очень большая"
        }

        private fun size(bytes: Long): String =
            if (bytes >= 1_000_000_000) "%.2f ГБ".format(bytes / 1_000_000_000.0)
            else "%.0f МБ".format(bytes / 1_000_000.0)
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_LOCAL = 1
        const val TYPE_CUSTOM = 2
    }
}
