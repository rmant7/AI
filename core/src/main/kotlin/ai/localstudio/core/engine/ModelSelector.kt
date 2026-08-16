package ai.localstudio.core.engine

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.DeviceProfile
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.ModelRegistry
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.SuitabilityScorer

data class SelectedModel(val model: ModelDescriptor, val binding: RuntimeBinding)

class NoModelForCapabilityException(val capability: Capability) :
    Exception("No installed model provides ${capability.id}")

/**
 * Answers "which model runs this stage" — the question the whole capability
 * design exists to make answerable.
 *
 * Only installed models are considered: a pipeline stage cannot wait for a
 * multi-gigabyte download. When nothing installed provides the capability, that
 * is reported as a missing capability, not as a missing model, so the UI can
 * offer the right fix ("install a model that can do OCR").
 */
class ModelSelector(
    private val registry: ModelRegistry,
    private val device: DeviceProfile,
    private val scorer: SuitabilityScorer = SuitabilityScorer(),
) {
    fun select(capability: Capability): SelectedModel {
        val ranked = scorer.rank(
            models = registry.providing(capability, onlyInstalled = true),
            device = device,
            capability = capability,
            limit = 1,
            installedIds = registry.installedIds(),
        )
        val best = ranked.firstOrNull() ?: throw NoModelForCapabilityException(capability)
        return SelectedModel(best.model, best.suitability.binding)
    }

    fun selectOrNull(capability: Capability): SelectedModel? =
        runCatching { select(capability) }.getOrNull()

    /**
     * Picks the first capability that anything installed can actually serve.
     * A request routed to `coding` still gets answered by a general model when
     * no coding-specialised one is installed.
     */
    fun selectAny(capabilities: List<Capability>): SelectedModel =
        capabilities.firstNotNullOfOrNull { selectOrNull(it) }
            ?: throw NoModelForCapabilityException(capabilities.last())
}
