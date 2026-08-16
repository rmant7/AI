package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.IncompatibilityReason
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.ModelFit
import ai.localstudio.core.registry.Suitability
import ai.localstudio.core.registry.SuitabilityScorer
import kotlinx.coroutines.launch

/**
 * Two lists, and the difference between them is the point.
 *
 * Local models can be downloaded and then run offline. The reference catalogue
 * below shows how the scorer judges models against this device — including the
 * ones that cannot run here and why, because a hidden model raises the question
 * "where is it?" and a visible one with a reason does not.
 */
class ModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelsBinding
    private lateinit var container: AppContainer
    private val adapter = RowAdapter(::onPrimary, ::onSecondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)
        binding.models.layoutManager = LinearLayoutManager(this)
        binding.models.adapter = adapter
        binding.deviceText.text = describeDevice(container.device)

        lifecycleScope.launch {
            container.downloads.state.collect { render() }
        }
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
        container.downloads.delete(seed)
        render()
    }

    /** Switching the provider is what makes the downloaded model answer. */
    private fun useLocally(seed: LocalModelSeed) {
        container.settings.providerId = CloudProviders.LOCAL.id
        container.settings.chatModel = seed.id
        Toast.makeText(this, "Чат переключён на ${seed.title}", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        val rows = mutableListOf<Row>()
        rows += Row.Header(getString(R.string.models_local_header))
        if (!LlamaBridge.isAvailable) {
            rows += Row.Header(getString(R.string.model_native_missing))
        }
        LocalModels.SEEDS.forEach { seed ->
            rows += Row.Local(
                seed = seed,
                state = container.downloads.stateOf(seed),
                installedBytes = container.modelStore.installedSize(seed),
                selected = container.settings.providerId == CloudProviders.LOCAL.id &&
                    container.settings.chatModel == seed.id,
            )
        }

        rows += Row.Header(getString(R.string.models_catalog_header))
        val scorer = SuitabilityScorer()
        container.catalog().models
            .map { catalogRow(it, container.device, scorer) }
            .sortedWith(compareByDescending<Row.Catalog> { it.score }.thenBy { it.model.id })
            .forEach { rows += it }

        adapter.submit(rows)
    }

    private fun describeDevice(device: DeviceProfile) = buildString {
        append("RAM: ${gb(device.totalRamBytes)} всего, ${gb(device.availableRamBytes)} свободно\n")
        append("Бюджет на модель: ${gb(device.usableRamBytes)} · ядер: ${device.cpuCores}\n")
        append("Свободно на диске: ${gb(device.availableStorageBytes)} · Android API ${device.androidApiLevel}\n")
        append("Runtime: ${container.runtimeLabel}")
    }

    private fun catalogRow(model: ModelDescriptor, device: DeviceProfile, scorer: SuitabilityScorer): Row.Catalog {
        val capability = model.capabilities.firstOrNull {
            it in listOf(Capability.TEXT_GENERATION, Capability.SPEECH_TO_TEXT, Capability.EMBEDDING)
        } ?: model.capabilities.first()

        val binding = model.bindings.minByOrNull { it.effectiveRequiredRamBytes }
        val fit = binding?.let { device.classifyFit(it.fileSizeBytes) } ?: ModelFit.TOO_LARGE

        return when (val suitability = scorer.evaluate(model, device, capability)) {
            is Suitability.Compatible -> Row.Catalog(
                model = model,
                specs = specs(model),
                verdict = "${label(fit)} · оценка ${"%.2f".format(suitability.breakdown.total)}",
                score = suitability.breakdown.total,
            )

            is Suitability.Incompatible -> Row.Catalog(
                model = model,
                specs = specs(model),
                verdict = explainIncompatible(suitability),
                score = -1.0,
            )
        }
    }

    private fun explainIncompatible(suitability: Suitability.Incompatible): String {
        val perRuntime = suitability.byRuntime
        if (perRuntime.size <= 1) {
            return "Не запустится: " + suitability.reasons.joinToString(", ") { explain(it) }
        }
        return "Не запустится:\n" + perRuntime.entries.joinToString("\n") { (runtime, reasons) ->
            "· ${runtime.id}: " + reasons.joinToString(", ") { explain(it) }
        }
    }

    private fun specs(model: ModelDescriptor): String {
        val binding = model.bindings.minByOrNull { it.effectiveRequiredRamBytes }
        val ram = binding?.let {
            gb(it.effectiveRequiredRamBytes) + if (it.isRamEstimated) " (оценка)" else ""
        } ?: "—"
        return buildString {
            append(model.capabilities.joinToString(", ") { it.id })
            append("\n")
            append("${model.quantization ?: "—"} · файл ${gb(binding?.fileSizeBytes ?: 0)} · RAM $ram")
            append("\n")
            append("runtime: ${model.bindings.joinToString(", ") { it.runtime.id }}")
        }
    }

    private fun label(fit: ModelFit): String = when (fit) {
        ModelFit.LIGHTWEIGHT -> "Лёгкая для этого устройства"
        ModelFit.RECOMMENDED -> "Рекомендуется"
        ModelFit.ADVANCED -> "Пойдёт, но без запаса"
        ModelFit.TOO_LARGE -> "Слишком большая"
    }

    private fun explain(reason: IncompatibilityReason): String = when (reason) {
        IncompatibilityReason.CAPABILITY_NOT_SUPPORTED -> "нет нужной capability"
        IncompatibilityReason.NO_SUPPORTED_RUNTIME -> "runtime не реализован в этой сборке"
        IncompatibilityReason.NOT_ENOUGH_RAM -> "не хватает RAM"
        IncompatibilityReason.NOT_ENOUGH_STORAGE -> "не хватает места"
        IncompatibilityReason.GPU_REQUIRED -> "нужен GPU"
        IncompatibilityReason.NPU_REQUIRED -> "нужен NPU"
        IncompatibilityReason.ANDROID_API_TOO_LOW -> "нужен более новый Android"
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
            val selected: Boolean,
        ) : Row

        data class Catalog(
            val model: ModelDescriptor,
            val specs: String,
            val verdict: String,
            val score: Double,
        ) : Row
    }

    private class RowAdapter(
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
            is Row.Catalog -> TYPE_CATALOG
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_LOCAL -> LocalHolder(ItemLocalModelBinding.inflate(inflater, parent, false))
                else -> CatalogHolder(ItemModelBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as CatalogHolder).bindHeader(row.title)
                is Row.Catalog -> (holder as CatalogHolder).bind(row)
                is Row.Local -> (holder as LocalHolder).bind(row, onPrimary, onSecondary)
            }
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_LOCAL = 1
            const val TYPE_CATALOG = 2
        }
    }

    private class CatalogHolder(val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bindHeader(title: String) {
            binding.modelName.text = title
            binding.modelSpecs.text = ""
            binding.modelSpecs.visibility = View.GONE
            binding.modelVerdict.text = ""
            binding.modelVerdict.visibility = View.GONE
        }

        fun bind(row: Row.Catalog) {
            binding.modelName.text = row.model.id
            binding.modelSpecs.text = row.specs
            binding.modelSpecs.visibility = View.VISIBLE
            binding.modelVerdict.text = row.verdict
            binding.modelVerdict.visibility = View.VISIBLE
        }
    }

    private class LocalHolder(val binding: ItemLocalModelBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            row: Row.Local,
            onPrimary: (LocalModelSeed) -> Unit,
            onSecondary: (LocalModelSeed) -> Unit,
        ) {
            val context = binding.root.context
            binding.localTitle.text = row.seed.title + if (row.selected) "  ✓" else ""
            binding.localSubtitle.text = "${row.seed.paramsLabel} · ${row.seed.repoId}\n${row.seed.note}"

            var progressVisible = false
            var secondaryVisible = false
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
                    "Поиск файла в ${state.repoId}…"
                }

                is DownloadState.Running -> {
                    progressVisible = true
                    primaryText = context.getString(R.string.model_cancel)
                    binding.localProgress.progress = (state.progress.fraction * 100).toInt()
                    val total = if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?"
                    "Загрузка: ${size(state.progress.bytesDownloaded)} из $total"
                }

                is DownloadState.Failed -> {
                    secondaryVisible = row.installedBytes > 0
                    "Ошибка: ${state.message}"
                }

                DownloadState.Idle -> ""
            }

            binding.localStatus.visibility =
                if (binding.localStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.localProgress.visibility = if (progressVisible) View.VISIBLE else View.GONE
            binding.localProgress.isIndeterminate = row.state is DownloadState.Resolving
            binding.localSecondaryButton.visibility = if (secondaryVisible) View.VISIBLE else View.GONE
            binding.localPrimaryButton.text = primaryText
            binding.localPrimaryButton.isEnabled =
                !(row.state is DownloadState.Installed && row.selected)

            binding.localPrimaryButton.setOnClickListener { onPrimary(row.seed) }
            binding.localSecondaryButton.setOnClickListener { onSecondary(row.seed) }
        }

        private fun size(bytes: Long): String =
            if (bytes >= 1_000_000_000) "%.2f ГБ".format(bytes / 1_000_000_000.0)
            else "%.0f МБ".format(bytes / 1_000_000.0)
    }
}
