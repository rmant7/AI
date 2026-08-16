package ai.localstudio.core.registry

/** How comfortably a model of a given artifact size is expected to run on a device. */
enum class ModelFit { LIGHTWEIGHT, RECOMMENDED, ADVANCED, TOO_LARGE }

/**
 * What the current device can actually run. Ranking is always relative to a
 * device — "the five best models" only means something once RAM, storage and
 * available accelerators are known.
 *
 * [performanceIndex] is 1.0 for the reference device used to measure
 * [RuntimeBinding.referenceTokensPerSecond]; a device twice as fast is 2.0.
 */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val availableStorageBytes: Long,
    val cpuCores: Int,
    val androidApiLevel: Int,
    val supportedRuntimes: Set<RuntimeKind>,
    val hasGpuDelegate: Boolean = false,
    val hasNpu: Boolean = false,
    val performanceIndex: Double = 1.0,
) {
    init {
        require(availableRamBytes in 0..totalRamBytes) { "availableRamBytes out of range" }
        require(performanceIndex > 0) { "performanceIndex must be positive" }
    }

    /**
     * RAM a model may occupy. The rest is left to the OS, the UI process and
     * whatever else is resident — loading up to the last free byte gets the
     * app killed on Android long before it gets slow.
     */
    val usableRamBytes: Long
        get() = (availableRamBytes * RAM_SAFETY_FACTOR).toLong()

    /**
     * Pre-download verdict: how a model of this artifact size sits against
     * *total* device RAM.
     *
     * This answers a different question from [usableRamBytes], which decides
     * whether a model can be loaded right now. Here the input is the only number
     * always available before downloading — the file size — and the thresholds
     * are deliberately generous, because the weights are just one part of what
     * has to fit: the KV cache, activations and everything already running take
     * the rest.
     *
     * Thresholds carried over from a working on-device implementation
     * (docs/03-model-registry.md), where they were chosen for exactly this reason.
     */
    fun classifyFit(artifactSizeBytes: Long): ModelFit {
        if (artifactSizeBytes <= 0 || totalRamBytes <= 0) return ModelFit.TOO_LARGE
        val recommendedMax = (totalRamBytes * RECOMMENDED_RAM_FRACTION).toLong()
        val advancedMax = (totalRamBytes * ADVANCED_RAM_FRACTION).toLong()
        val lightweightMax = (recommendedMax * LIGHTWEIGHT_OF_RECOMMENDED_FRACTION).toLong()
        return when {
            artifactSizeBytes <= lightweightMax -> ModelFit.LIGHTWEIGHT
            artifactSizeBytes <= recommendedMax -> ModelFit.RECOMMENDED
            artifactSizeBytes <= advancedMax -> ModelFit.ADVANCED
            else -> ModelFit.TOO_LARGE
        }
    }

    companion object {
        const val RAM_SAFETY_FACTOR = 0.6

        /** Weights under ~35% of total RAM leave comfortable room for KV cache and activations. */
        const val RECOMMENDED_RAM_FRACTION = 0.35

        /** Up to ~55%: will probably run, slowly and with nothing left over. */
        const val ADVANCED_RAM_FRACTION = 0.55

        /** Meaningfully below the comfortable ceiling, not merely under it — so it reads as fast. */
        const val LIGHTWEIGHT_OF_RECOMMENDED_FRACTION = 0.4
    }
}
