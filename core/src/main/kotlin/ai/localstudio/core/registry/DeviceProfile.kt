package ai.localstudio.core.registry

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

    companion object {
        const val RAM_SAFETY_FACTOR = 0.6
    }
}
