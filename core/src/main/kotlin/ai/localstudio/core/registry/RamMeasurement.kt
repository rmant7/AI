package ai.localstudio.core.registry

import kotlin.math.max

/**
 * What a model actually cost this process in RAM, measured on this device —
 * the replacement for [RuntimeBinding.effectiveRequiredRamBytes]'s
 * file-size × 1.3 guess once at least one real run exists.
 *
 * [peakBytes] is the largest observed peak growth of the process's own
 * resident set across a load plus its first full generation (weights paged
 * in, KV cache, encoder activations), not the drop in system-wide free RAM,
 * which also moves with every other app and the kernel's own reclaim.
 * Repeated measurements keep the maximum, never an average: admission
 * should err toward refusing, not toward an OOM kill.
 *
 * Real device report behind this: MADLAD-400 7B's file is 5.98 GB, so the
 * old estimate said 7.78 GB and refused it at a 7.0–7.35 GB budget — yet
 * the same model loaded and ran using ~5.3 GB.
 */
data class RamMeasurement(val peakBytes: Long, val sampleCount: Int) {
    init {
        require(peakBytes > 0) { "peakBytes must be positive" }
        require(sampleCount > 0) { "sampleCount must be positive" }
    }

    fun merge(newPeakBytes: Long): RamMeasurement =
        RamMeasurement(max(peakBytes, newPeakBytes), sampleCount + 1)

    /**
     * [peakBytes] plus a safety margin — wider while only a single run has
     * been measured, since one run can land low (allocator state, how much
     * of the file happened to be paged in, a short first prompt). Initial
     * values, meant to be retuned from RAM_MEASURE logs.
     */
    val requiredBytes: Long
        get() = if (sampleCount <= 1) {
            max((peakBytes * SINGLE_SAMPLE_FACTOR).toLong(), peakBytes + SINGLE_SAMPLE_MIN_MARGIN_BYTES)
        } else {
            max((peakBytes * CONFIRMED_FACTOR).toLong(), peakBytes + CONFIRMED_MIN_MARGIN_BYTES)
        }

    companion object {
        const val SINGLE_SAMPLE_FACTOR = 1.2
        const val SINGLE_SAMPLE_MIN_MARGIN_BYTES = 768L * 1024 * 1024
        const val CONFIRMED_FACTOR = 1.1
        const val CONFIRMED_MIN_MARGIN_BYTES = 512L * 1024 * 1024
    }
}
