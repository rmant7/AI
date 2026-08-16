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
    /**
     * Share of total RAM a model may claim. The conservative default suits a
     * device shared with other apps; a user who wants the phone to be an
     * inference machine can raise it, and on a 16 GB device that is the
     * difference between a 4B model and a 27B one.
     */
    val ramBudgetFraction: Double = BASE_RAM_FRACTION,
) {
    init {
        require(availableRamBytes in 0..totalRamBytes) { "availableRamBytes out of range" }
        require(performanceIndex > 0) { "performanceIndex must be positive" }
        require(ramBudgetFraction in 0.05..MAX_RAM_FRACTION) {
            "ramBudgetFraction must be between 0.05 and $MAX_RAM_FRACTION"
        }
    }

    /**
     * RAM a model may occupy.
     *
     * Derived primarily from **total** memory, not from what is free right now.
     * Android reports `availMem` as "free this instant" while keeping large
     * amounts in cached processes that it evicts on demand: a 16 GB phone
     * routinely reports under 2 GB free and still loads a 4 GB model without
     * trouble. Budgeting off `availMem` alone therefore punishes exactly the
     * devices that can run the most — measured on a real 15 GB device, which
     * was offered a 1.1 GB budget.
     *
     * Free memory still counts, as the higher of the two: a device that
     * genuinely has a lot free right now may use it. The result is capped so
     * that a model never claims more than [MAX_RAM_FRACTION] of the machine —
     * the OS, the UI process and the other stages of a pipeline live in the
     * rest, and loading up to the last byte gets the app killed long before it
     * gets slow.
     */
    val usableRamBytes: Long
        get() = maxOf(
            (totalRamBytes * ramBudgetFraction).toLong(),
            (availableRamBytes * FREE_RAM_SAFETY_FACTOR).toLong(),
        ).coerceAtMost((totalRamBytes * MAX_RAM_FRACTION).toLong())

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
        /** Of total RAM: what a model may plan for regardless of momentary free memory. */
        const val BASE_RAM_FRACTION = 0.35

        /** Of genuinely free RAM, when that is the larger figure. */
        const val FREE_RAM_SAFETY_FACTOR = 0.6

        /**
         * Absolute ceiling. Even a user who wants everything cannot have the
         * last 5%: the OS, the UI process and the file cache the model itself
         * is mapped through live there, and taking it means being killed
         * mid-answer rather than running slowly.
         */
        const val MAX_RAM_FRACTION = 0.95

        /** Weights under ~35% of total RAM leave comfortable room for KV cache and activations. */
        const val RECOMMENDED_RAM_FRACTION = 0.35

        /** Up to ~55%: will probably run, slowly and with nothing left over. */
        const val ADVANCED_RAM_FRACTION = 0.55

        /** Meaningfully below the comfortable ceiling, not merely under it — so it reads as fast. */
        const val LIGHTWEIGHT_OF_RECOMMENDED_FRACTION = 0.4
    }
}
