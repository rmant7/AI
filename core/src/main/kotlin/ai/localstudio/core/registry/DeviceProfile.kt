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
    /**
     * Share of [availableRamBytes] a model may claim when that is the larger
     * figure. The default is for a plain `availMem` snapshot; a caller that
     * measures free RAM more precisely (the kernel's MemAvailable plus what
     * its own evictable models hold) can trust more of it.
     */
    val freeRamSafetyFactor: Double = FREE_RAM_SAFETY_FACTOR,
) {
    init {
        require(freeRamSafetyFactor > 0 && freeRamSafetyFactor <= 1.0) { "freeRamSafetyFactor must be in (0, 1]" }
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
            (availableRamBytes * freeRamSafetyFactor).toLong(),
        ).coerceAtMost((totalRamBytes * MAX_RAM_FRACTION).toLong())

    /**
     * What a load could actually get *right now* — the load-time
     * counterpart to [usableRamBytes], which is deliberately generous: a
     * user-set policy ceiling ("what this device is allowed to reach for"),
     * not a promise that memory is physically there this instant.
     * [usableRamBytes]'s `max(totalRamBytes * ramBudgetFraction, ...)` term
     * exists specifically so raising [ramBudgetFraction] can clear that
     * floor regardless of live memory — useful for what the Models screen
     * labels "Recommended", fatal for an admission check that actually
     * triggers a native allocation. Real device report, same night, same
     * mechanism three times: raising the RAM percentage to 80% made
     * MADLAD-400 7B clear [usableRamBytes] (13 GB) while only ~5 GB was
     * genuinely free; the weights loaded, then the OOM killer took the
     * whole process out the moment generation allocated anything more.
     * This is just [usableRamBytes]'s own live term, isolated: no policy
     * floor to clear, only what [freeRamSafetyFactor] says is safe to trust
     * of what's actually free (plus this app's own evictable models,
     * already folded into [availableRamBytes] by the caller — see
     * `AppContainer.profileOf`).
     */
    val liveRamBytes: Long
        get() = (availableRamBytes * freeRamSafetyFactor).toLong()

    /**
     * Whether a model of this artifact size can actually be loaded under the
     * user's current RAM budget ([usableRamBytes]) — the one authoritative
     * check [classifyFit] and the Models screen's own download-time gate both
     * defer to, so a model can never be labelled a safe pick by one and
     * refused by the other. The 1.3x multiplier mirrors
     * [RuntimeBinding.effectiveRequiredRamBytes]'s own unmeasured-binding
     * estimate: weights are the bulk of the footprint but not all of it — the
     * KV cache, activations and the loader's own copies sit on top.
     */
    fun fitsBudget(artifactSizeBytes: Long): Boolean =
        artifactSizeBytes <= 0 || artifactSizeBytes * ESTIMATE_NUMERATOR / ESTIMATE_DENOMINATOR <= usableRamBytes

    /**
     * Pre-download verdict: how a model of this artifact size sits against
     * this device, both in absolute terms (*total* RAM — a 2 GB model reads
     * as lightweight on any phone worth running it on) and against the
     * user's actual, adjustable budget: a size [fitsBudget] itself rejects is
     * always TOO_LARGE here too, however small a fraction of total RAM it
     * is — a model this screen calls "Recommended" must never be one the
     * download-time RAM gate turns around and refuses. Real device report:
     * lowering the RAM budget percentage still showed several models as
     * "Recommended" that immediately failed to load, because this used to
     * classify off a fixed fraction of total RAM with no awareness of the
     * budget the user had actually set.
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
        if (!fitsBudget(artifactSizeBytes)) return ModelFit.TOO_LARGE
        val recommendedMax = (totalRamBytes * RECOMMENDED_RAM_FRACTION).toLong()
        val lightweightMax = (recommendedMax * LIGHTWEIGHT_OF_RECOMMENDED_FRACTION).toLong()
        return when {
            artifactSizeBytes <= lightweightMax -> ModelFit.LIGHTWEIGHT
            artifactSizeBytes <= recommendedMax -> ModelFit.RECOMMENDED
            else -> ModelFit.ADVANCED
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

        /** Meaningfully below the comfortable ceiling, not merely under it — so it reads as fast. */
        const val LIGHTWEIGHT_OF_RECOMMENDED_FRACTION = 0.4

        /**
         * File size -> estimated footprint, in [fitsBudget]. Matches
         * [RuntimeBinding.ESTIMATED_RAM_NUMERATOR]/[RuntimeBinding.ESTIMATED_RAM_DENOMINATOR]
         * (an unmeasured binding's own pessimistic RAM estimate) rather than
         * inventing a second ratio for what is the same estimate.
         */
        const val ESTIMATE_NUMERATOR = 13L
        const val ESTIMATE_DENOMINATOR = 10L
    }
}
