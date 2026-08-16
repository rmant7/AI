package ai.localstudio.core.registry

import ai.localstudio.core.capability.Capability
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Why a model cannot run here. Surfaced in the UI instead of silently hiding the model. */
enum class IncompatibilityReason {
    CAPABILITY_NOT_SUPPORTED,
    NO_SUPPORTED_RUNTIME,
    NOT_ENOUGH_RAM,
    NOT_ENOUGH_STORAGE,
    GPU_REQUIRED,
    NPU_REQUIRED,
    ANDROID_API_TOO_LOW,
}

data class ScoreBreakdown(
    val quality: Double,
    val speed: Double,
    val memory: Double,
    val total: Double,
)

sealed interface Suitability {
    data class Compatible(
        val binding: RuntimeBinding,
        val breakdown: ScoreBreakdown,
        val estimatedTokensPerSecond: Double?,
    ) : Suitability

    data class Incompatible(val reasons: Set<IncompatibilityReason>) : Suitability
}

data class RankedModel(
    val model: ModelDescriptor,
    val suitability: Suitability.Compatible,
) {
    val score: Double get() = suitability.breakdown.total
}

/**
 * Ranks models for one device and one capability.
 *
 * Hard constraints (RAM, storage, runtime, accelerators) are filters, not
 * penalties — a model that cannot load is not a worse choice, it is not a
 * choice. Everything that survives is scored as a weighted geometric mean of
 * quality, speed and memory headroom, so a near-zero factor sinks the model
 * instead of being averaged away by the other two.
 *
 * Memory is scored as headroom rather than as a yes/no fit: a model that
 * consumes the whole budget leaves nothing for the other stages of a pipeline
 * (ASR, embeddings, a VLM), so it has to earn that place on quality.
 */
class SuitabilityScorer(
    private val qualityWeight: Double = 0.5,
    private val speedWeight: Double = 0.3,
    private val memoryWeight: Double = 0.2,
    private val targetTokensPerSecond: Double = 25.0,
) {
    init {
        require(qualityWeight > 0 && speedWeight > 0 && memoryWeight > 0) {
            "Weights must be positive"
        }
    }

    fun evaluate(
        model: ModelDescriptor,
        device: DeviceProfile,
        capability: Capability,
        alreadyInstalled: Boolean = false,
    ): Suitability {
        if (!model.supports(capability)) {
            return Suitability.Incompatible(setOf(IncompatibilityReason.CAPABILITY_NOT_SUPPORTED))
        }

        val rejections = mutableSetOf<IncompatibilityReason>()
        val candidates = mutableListOf<RuntimeBinding>()
        for (binding in model.bindings) {
            val reasons = reject(binding, device, alreadyInstalled)
            if (reasons.isEmpty()) candidates += binding else rejections += reasons
        }
        if (candidates.isEmpty()) {
            return Suitability.Incompatible(rejections)
        }

        return candidates
            .map { binding -> score(model, binding, device, capability) }
            .maxBy { it.breakdown.total }
    }

    /** Best [limit] models for [capability] on [device], best first. */
    fun rank(
        models: Iterable<ModelDescriptor>,
        device: DeviceProfile,
        capability: Capability,
        limit: Int = 5,
        installedIds: Set<String> = emptySet(),
    ): List<RankedModel> = models
        .mapNotNull { model ->
            val suitability = evaluate(model, device, capability, model.id in installedIds)
            (suitability as? Suitability.Compatible)?.let { RankedModel(model, it) }
        }
        .sortedWith(compareByDescending<RankedModel> { it.score }.thenBy { it.model.id })
        .take(limit)

    private fun reject(
        binding: RuntimeBinding,
        device: DeviceProfile,
        alreadyInstalled: Boolean,
    ): Set<IncompatibilityReason> = buildSet {
        if (binding.runtime !in device.supportedRuntimes) add(IncompatibilityReason.NO_SUPPORTED_RUNTIME)
        if (binding.requiredRamBytes > device.usableRamBytes) add(IncompatibilityReason.NOT_ENOUGH_RAM)
        if (!alreadyInstalled && binding.fileSizeBytes > device.availableStorageBytes) {
            add(IncompatibilityReason.NOT_ENOUGH_STORAGE)
        }
        if (binding.requiresGpu && !device.hasGpuDelegate) add(IncompatibilityReason.GPU_REQUIRED)
        if (binding.requiresNpu && !device.hasNpu) add(IncompatibilityReason.NPU_REQUIRED)
        if (binding.minAndroidApi > device.androidApiLevel) add(IncompatibilityReason.ANDROID_API_TOO_LOW)
    }

    private fun score(
        model: ModelDescriptor,
        binding: RuntimeBinding,
        device: DeviceProfile,
        capability: Capability,
    ): Suitability.Compatible {
        val quality = (model.benchmarks.scoreFor(capability) ?: DEFAULT_QUALITY) / 100.0
        val estimatedTps = binding.referenceTokensPerSecond?.times(device.performanceIndex)
        val speed = estimatedTps?.let { min(1.0, it / targetTokensPerSecond) } ?: DEFAULT_SPEED
        val memory = 1.0 - binding.requiredRamBytes.toDouble() / device.usableRamBytes.toDouble()

        val total = geometricMean(
            values = doubleArrayOf(quality, speed, memory),
            weights = doubleArrayOf(qualityWeight, speedWeight, memoryWeight),
        )
        return Suitability.Compatible(
            binding = binding,
            breakdown = ScoreBreakdown(quality, speed, memory, total),
            estimatedTokensPerSecond = estimatedTps,
        )
    }

    private fun geometricMean(values: DoubleArray, weights: DoubleArray): Double {
        val weightSum = weights.sum()
        val logSum = values.indices.sumOf { i ->
            weights[i] * ln(values[i].coerceIn(EPSILON, 1.0))
        }
        return exp(logSum / weightSum)
    }

    private companion object {
        /** Unbenchmarked models are neither promoted nor buried. */
        const val DEFAULT_QUALITY = 60.0
        const val DEFAULT_SPEED = 0.5
        const val EPSILON = 1e-6
    }
}
