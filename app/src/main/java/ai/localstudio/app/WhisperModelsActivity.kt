package ai.localstudio.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.localstudio.app.databinding.ActivityWhisperModelsBinding
import ai.localstudio.app.databinding.ItemLocalModelBinding
import ai.localstudio.app.whisper.WhisperDownloadState
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.app.whisper.WhisperModels
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.ModelFit
import kotlinx.coroutines.launch

/**
 * One row per size, each with its own download/cancel/delete controls —
 * the compact shared button row this replaced gave every size a button that
 * looked the same whether it was idle, downloading, or installed, with no
 * way to tell which one a tap would act on.
 */
class WhisperModelsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWhisperModelsBinding
    private lateinit var container: AppContainer
    private val adapter = RowAdapter(::onPrimary, ::onSecondary)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhisperModelsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        container = AppContainer.get(this)
        binding.whisperModels.layoutManager = LinearLayoutManager(this)
        binding.whisperModels.adapter = adapter

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

    private fun onPrimary(seed: WhisperModelSeed) {
        val state = container.whisperDownloads.stateOf(seed)
        when {
            state is WhisperDownloadState.Installed -> useThis(seed)
            state is WhisperDownloadState.Running -> container.whisperDownloads.cancel(seed)
            else -> container.whisperDownloads.start(seed)
        }
    }

    private fun onSecondary(seed: WhisperModelSeed) {
        when (val state = container.whisperDownloads.stateOf(seed)) {
            is WhisperDownloadState.Failed -> AlertDialog.Builder(this)
                .setTitle(seed.title)
                .setMessage(state.message)
                .setPositiveButton("Понятно", null)
                .show()

            else -> container.whisperDownloads.delete(seed)
        }
    }

    private fun useThis(seed: WhisperModelSeed) {
        container.settings.whisperModelId = seed.id
        Toast.makeText(this, "Голосовой ввод переключён на ${seed.title}", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun render() {
        val device = container.device
        binding.whisperHint.text = describeDevice(device)

        val states = container.whisperDownloads.state.value
        val anyRunning = states.values.any { it is WhisperDownloadState.Running }
        val selectedId = container.settings.whisperModelId
        val installed = container.whisperStore.installedSeed(selectedId)

        val rows = WhisperModels.SEEDS.map { seed ->
            Row(
                seed = seed,
                state = container.whisperDownloads.stateOf(seed),
                selected = installed?.id == seed.id,
                downloadBlocked = anyRunning && container.whisperDownloads.stateOf(seed) is WhisperDownloadState.Idle,
                fit = device.classifyFit(seed.approxSizeBytes),
            )
        }
        adapter.submit(rows)
    }

    private fun describeDevice(device: DeviceProfile): String = buildString {
        append("RAM: ${gb(device.totalRamBytes)} всего, ${gb(device.availableRamBytes)} свободно\n")
        append(getString(R.string.settings_whisper_note))
    }

    private fun gb(bytes: Long): String =
        if (bytes >= 1_000_000_000) "%.1f ГБ".format(bytes / 1_000_000_000.0)
        else "%.0f МБ".format(bytes / 1_000_000.0)

    data class Row(
        val seed: WhisperModelSeed,
        val state: WhisperDownloadState,
        val selected: Boolean,
        val downloadBlocked: Boolean,
        val fit: ModelFit,
    )

    private inner class RowAdapter(
        private val onPrimary: (WhisperModelSeed) -> Unit,
        private val onSecondary: (WhisperModelSeed) -> Unit,
    ) : RecyclerView.Adapter<Holder>() {

        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemLocalModelBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position], onPrimary, onSecondary)
        }
    }

    private class Holder(val binding: ItemLocalModelBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            row: Row,
            onPrimary: (WhisperModelSeed) -> Unit,
            onSecondary: (WhisperModelSeed) -> Unit,
        ) {
            val context = binding.root.context
            binding.localTitle.text = row.seed.title + if (row.selected) "  ✓" else ""
            binding.localSubtitle.text = "~${size(row.seed.approxSizeBytes)} · ${fitLabel(row.fit)}"

            var progressVisible = false
            var secondaryVisible = false
            var secondaryText = context.getString(R.string.model_delete)
            var primaryText = "Скачать"
            var primaryEnabled = true

            binding.localStatus.text = when (val state = row.state) {
                is WhisperDownloadState.Installed -> {
                    secondaryVisible = true
                    primaryText = if (row.selected) "Используется" else "Использовать"
                    primaryEnabled = !row.selected
                    "Установлена"
                }

                is WhisperDownloadState.Running -> {
                    progressVisible = true
                    primaryText = "Отменить"
                    binding.localProgress.progress = (state.progress.fraction * 100).toInt()
                    val total = if (state.progress.bytesTotal > 0) size(state.progress.bytesTotal) else "?"
                    "${state.stage}: ${size(state.progress.bytesDownloaded)} из $total"
                }

                is WhisperDownloadState.Failed -> {
                    secondaryVisible = true
                    secondaryText = context.getString(R.string.model_details)
                    primaryText = "Повторить"
                    "Ошибка: " + state.message.lineSequence().first()
                }

                WhisperDownloadState.Idle -> {
                    if (row.downloadBlocked) {
                        primaryEnabled = false
                        "Дождитесь окончания другой загрузки"
                    } else {
                        ""
                    }
                }
            }

            binding.localStatus.visibility =
                if (binding.localStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.localProgress.visibility = if (progressVisible) View.VISIBLE else View.GONE
            binding.localSecondaryButton.visibility = if (secondaryVisible) View.VISIBLE else View.GONE
            binding.localSecondaryButton.text = secondaryText
            binding.localPrimaryButton.text = primaryText
            binding.localPrimaryButton.isEnabled = primaryEnabled

            binding.localPrimaryButton.setOnClickListener { onPrimary(row.seed) }
            binding.localSecondaryButton.setOnClickListener { onSecondary(row.seed) }
        }

        private fun size(bytes: Long): String =
            if (bytes >= 1_000_000_000) "%.2f ГБ".format(bytes / 1_000_000_000.0)
            else "%.0f МБ".format(bytes / 1_000_000.0)

        private fun fitLabel(fit: ModelFit): String = when (fit) {
            ModelFit.LIGHTWEIGHT -> "лёгкая"
            ModelFit.RECOMMENDED -> "рекомендуется"
            ModelFit.ADVANCED -> "тяжёлая, но пойдёт"
            ModelFit.TOO_LARGE -> "очень большая"
        }
    }
}
