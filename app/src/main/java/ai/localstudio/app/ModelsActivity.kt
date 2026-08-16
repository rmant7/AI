package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityModelsBinding
import ai.localstudio.app.databinding.ItemModelBinding
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.IncompatibilityReason
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.ModelFit
import ai.localstudio.core.registry.Suitability
import ai.localstudio.core.registry.SuitabilityScorer

/**
 * Runs the scorer against the real device and shows its verdict for every model
 * in the shipped catalog — including the models that cannot run here and why.
 * A hidden model raises the question "where is it?"; a visible one with a
 * reason does not.
 */
class ModelsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val container = AppContainer.get(this)
        val device = container.device
        val scorer = SuitabilityScorer()

        binding.deviceText.text = buildString {
            append("RAM: ${gb(device.totalRamBytes)} всего, ${gb(device.availableRamBytes)} свободно\n")
            append("Бюджет на модель: ${gb(device.usableRamBytes)} · ядер: ${device.cpuCores}\n")
            append("Свободно на диске: ${gb(device.availableStorageBytes)} · Android API ${device.androidApiLevel}\n")
            append("Runtime: ${container.runtimeLabel}")
        }

        val rows = container.catalog().models
            .map { model -> row(model, device, scorer) }
            .sortedWith(compareByDescending<Row> { it.score }.thenBy { it.model.id })

        binding.models.layoutManager = LinearLayoutManager(this)
        binding.models.adapter = ModelAdapter(rows)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun row(model: ModelDescriptor, device: DeviceProfile, scorer: SuitabilityScorer): Row {
        val capability = model.capabilities.firstOrNull {
            it in listOf(Capability.TEXT_GENERATION, Capability.SPEECH_TO_TEXT, Capability.EMBEDDING)
        } ?: model.capabilities.first()

        val binding = model.bindings.minByOrNull { it.effectiveRequiredRamBytes }
        val fit = binding?.let { device.classifyFit(it.fileSizeBytes) } ?: ModelFit.TOO_LARGE

        return when (val suitability = scorer.evaluate(model, device, capability)) {
            is Suitability.Compatible -> Row(
                model = model,
                specs = specs(model),
                verdict = "${label(fit)} · оценка ${"%.2f".format(suitability.breakdown.total)}",
                score = suitability.breakdown.total,
            )

            is Suitability.Incompatible -> Row(
                model = model,
                specs = specs(model),
                verdict = "Не запустится: " + suitability.reasons.joinToString(", ") { explain(it) },
                score = -1.0,
            )
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

    data class Row(
        val model: ModelDescriptor,
        val specs: String,
        val verdict: String,
        val score: Double,
    )

    private class ModelAdapter(private val rows: List<Row>) : RecyclerView.Adapter<ModelAdapter.Holder>() {

        class Holder(val binding: ItemModelBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.binding.modelName.text = row.model.id
            holder.binding.modelSpecs.text = row.specs
            holder.binding.modelVerdict.text = row.verdict
        }
    }
}
