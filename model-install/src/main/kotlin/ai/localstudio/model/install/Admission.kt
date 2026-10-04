package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRole
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.RuntimeBinding

/** Whether something may start given the resources it needs. */
sealed interface Admission {
    data object Admit : Admission

    data class Deny(val neededBytes: Long, val availableBytes: Long) : Admission

    /** The need isn't known (no size anywhere); the caller decides — legacy downloads proceeded. */
    data class Unknown(val reason: String) : Admission
}

object ResourceAdmission {

    /** Headroom kept free on top of what an install needs — ModelDownloads' slack, the larger legacy one. */
    const val STORAGE_SLACK_BYTES = 500L * 1024 * 1024

    /**
     * May an install that still has to write [neededBytes] (downloads not yet
     * on disk plus unpacked sizes) start, with [freeBytes] free? Null need =
     * unknown size.
     */
    fun storage(neededBytes: Long?, freeBytes: Long, slackBytes: Long = STORAGE_SLACK_BYTES): Admission = when {
        neededBytes == null -> Admission.Unknown("size unknown")
        neededBytes + slackBytes > freeBytes -> Admission.Deny(neededBytes + slackBytes, freeBytes)
        else -> Admission.Admit
    }

    /**
     * May [variant] be loaded with the artifact [roles] given (the binding's
     * required roles plus any optional ones in use, e.g. a projector for
     * vision), with [availableBytes] of memory? A measured peak wins; else
     * the files' sizes times [ai.localstudio.model.ResourceRequirements.ramEstimateFactor].
     * [installedSizes] are the real sizes from the install manifest, by role.
     */
    fun memory(
        variant: ModelVariant,
        roles: Set<ArtifactRole>,
        installedSizes: Map<ArtifactRole, Long>,
        availableBytes: Long,
    ): Admission {
        val need = variant.resources.measuredPeakRamBytes ?: run {
            val sizes = roles.map { installedSizes[it] ?: 0L }
            if (sizes.any { it <= 0 }) return Admission.Unknown("size of ${roles.filter { (installedSizes[it] ?: 0L) <= 0 }} unknown")
            (sizes.sum() * variant.resources.ramEstimateFactor).toLong()
        }
        return if (need > availableBytes) Admission.Deny(need, availableBytes) else Admission.Admit
    }
}

/** Why a binding can't run here. */
enum class BindingProblem {
    RUNTIME_NOT_SHIPPED,
    ANDROID_TOO_OLD,
    CPU_FEATURES_MISSING,
    GPU_REQUIRED,
    NPU_REQUIRED,
    REQUIRED_ROLES_NOT_INSTALLED,
}

sealed interface RuntimeChoice {
    /** [optionalRoles]: the binding's optional roles that are installed and so can be used. */
    data class Chosen(val binding: RuntimeBinding, val optionalRoles: Set<ArtifactRole>) : RuntimeChoice

    data class Unavailable(val problems: Map<RuntimeBinding, Set<BindingProblem>>) : RuntimeChoice
}

/**
 * Picks how to run an installed variant on this device: the first binding,
 * in catalog order, whose runtime ships in this build and whose device
 * requirements and required artifacts are all met.
 */
object BindingSelector {

    fun choose(variant: ModelVariant, installedRoles: Set<ArtifactRole>, device: DeviceProfile): RuntimeChoice {
        val problems = linkedMapOf<RuntimeBinding, Set<BindingProblem>>()
        for (binding in variant.bindings) {
            val found = problemsOf(binding, installedRoles, device)
            if (found.isEmpty()) return RuntimeChoice.Chosen(binding, binding.optionalRoles intersect installedRoles)
            problems[binding] = found
        }
        return RuntimeChoice.Unavailable(problems)
    }

    fun problemsOf(binding: RuntimeBinding, installedRoles: Set<ArtifactRole>, device: DeviceProfile): Set<BindingProblem> = buildSet {
        if (binding.runtime !in device.runtimes) add(BindingProblem.RUNTIME_NOT_SHIPPED)
        if (device.androidApi < binding.minAndroidApi) add(BindingProblem.ANDROID_TOO_OLD)
        if (!device.cpuFeatures.containsAll(binding.cpuFeatures)) add(BindingProblem.CPU_FEATURES_MISSING)
        if (binding.requiresGpu && !device.hasGpu) add(BindingProblem.GPU_REQUIRED)
        if (binding.requiresNpu && !device.hasNpu) add(BindingProblem.NPU_REQUIRED)
        if (!installedRoles.containsAll(binding.requiredRoles)) add(BindingProblem.REQUIRED_ROLES_NOT_INSTALLED)
    }
}
