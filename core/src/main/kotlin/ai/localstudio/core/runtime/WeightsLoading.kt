package ai.localstudio.core.runtime

/**
 * How a local model's weights get into memory: mapped from their file, or
 * read into the process's own memory. Which one costs less depends on the
 * model, not on the app -- measured on a Pixel 10 Pro (#485/#488):
 * - Qwen2.5-VL-7B Q4_K_M: mapped, +3.9 GB anonymous (llama.cpp's repacked
 *   copy of the weights) on top of 4.2 GB of the file's own pages -- the same
 *   weights counted twice; read into memory, +4.8 GB in all, and the full
 *   vision lifecycle passed where mapped it was refused on reload.
 * - Gemma 4 E2B QAT q4_0: mapped, free RAM fell only ~1.4 GB (its large
 *   per-layer embedding tables stay in the file and are read sparsely); read
 *   into memory, ~3.55 GB.
 */
enum class WeightsLoading {
    /** Decided per model from its own measurement; mapped until one exists. */
    AUTO,
    MAPPED,
    IN_MEMORY,
}

data class WeightsLoadDecision(val mapped: Boolean, val reason: String)

object WeightsLoadPolicy {

    /**
     * Above this share of the file's size, the anonymous growth of a mapped
     * load means llama.cpp copied the weights out of the file (CPU repacking):
     * the mapped pages are then mostly read once, and mapping only makes the
     * same weights count twice. An internal heuristic from three models
     * (Qwen 0.84, Index-Translate 1.05, Gemma ~0.4) -- not a contract; to be
     * replaced by choosing on measured admission and speed per mode.
     */
    const val COPIED_SHARE = 0.5

    /**
     * [mappedAnonymousBytes]: the anonymous growth this model's file showed
     * the last time it was loaded mapped and measured; null when it never
     * was. [fileBytes]: the model file's size.
     */
    fun decide(setting: WeightsLoading, mappedAnonymousBytes: Long?, fileBytes: Long): WeightsLoadDecision = when (setting) {
        WeightsLoading.MAPPED -> WeightsLoadDecision(true, "set to map from file")
        WeightsLoading.IN_MEMORY -> WeightsLoadDecision(false, "set to read into memory")
        WeightsLoading.AUTO -> when {
            mappedAnonymousBytes == null || fileBytes <= 0 ->
                WeightsLoadDecision(true, "auto: not measured yet, mapped (this load measures it)")
            else -> {
                val share = mappedAnonymousBytes.toDouble() / fileBytes
                val shareText = "%.2f".format(java.util.Locale.ROOT, share)
                if (share > COPIED_SHARE) {
                    WeightsLoadDecision(false, "auto: weights copied when mapped (anonymous/file = $shareText), read into memory")
                } else {
                    WeightsLoadDecision(true, "auto: mapped pages carry the weights (anonymous/file = $shareText), mapped")
                }
            }
        }
    }
}
