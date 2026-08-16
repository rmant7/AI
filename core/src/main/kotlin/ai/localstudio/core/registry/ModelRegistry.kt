package ai.localstudio.core.registry

import ai.localstudio.core.capability.Capability

/** Where a model currently is from the application's point of view. */
enum class InstallState { INSTALLED, AVAILABLE }

data class RegistryEntry(
    val model: ModelDescriptor,
    val state: InstallState,
    val installedPath: String? = null,
    val lastSeenAt: String? = null,
)

/** A model in the remote catalog that is a candidate replacement for an installed one. */
data class ModelUpdate(
    val installed: ModelDescriptor,
    val candidate: ModelDescriptor,
    val qualityDelta: Double,
    val speedDelta: Double,
    val ramDelta: Long,
)

/**
 * The catalog of everything the app knows about, installed or not.
 *
 * The registry never downloads and never deletes: [diff] reports what changed
 * upstream and the user decides. Automatic catalog refresh is desirable;
 * automatic downloads turn a phone into a model graveyard.
 */
class ModelRegistry(entries: List<RegistryEntry> = emptyList()) {

    private val entries = LinkedHashMap<String, RegistryEntry>().apply {
        entries.forEach { put(it.model.id, it) }
    }

    fun all(): List<RegistryEntry> = entries.values.toList()

    fun installed(): List<RegistryEntry> = entries.values.filter { it.state == InstallState.INSTALLED }

    fun available(): List<RegistryEntry> = entries.values.filter { it.state == InstallState.AVAILABLE }

    fun find(modelId: String): RegistryEntry? = entries[modelId]

    fun installedIds(): Set<String> = installed().map { it.model.id }.toSet()

    fun providing(capability: Capability, onlyInstalled: Boolean = false): List<ModelDescriptor> =
        entries.values
            .filter { !onlyInstalled || it.state == InstallState.INSTALLED }
            .map { it.model }
            .filter { it.supports(capability) }

    fun upsert(entry: RegistryEntry) {
        entries[entry.model.id] = entry
    }

    fun markInstalled(modelId: String, path: String) {
        val entry = entries[modelId] ?: throw IllegalArgumentException("Unknown model: $modelId")
        entries[modelId] = entry.copy(state = InstallState.INSTALLED, installedPath = path)
    }

    /**
     * Merges a freshly fetched [catalog] into the registry and reports what is new.
     *
     * Installed entries keep their state and path: a catalog refresh must never
     * be able to uninstall anything.
     */
    fun merge(catalog: ModelCatalog): List<ModelDescriptor> {
        val newModels = mutableListOf<ModelDescriptor>()
        for (model in catalog.models) {
            val existing = entries[model.id]
            if (existing == null) {
                entries[model.id] = RegistryEntry(model, InstallState.AVAILABLE, lastSeenAt = catalog.updatedAt)
                newModels += model
            } else {
                entries[model.id] = existing.copy(model = model, lastSeenAt = catalog.updatedAt)
            }
        }
        return newModels
    }

    /**
     * Candidate upgrades: newer members of a family that an installed model
     * belongs to. Deltas are reported, not acted on — a newer model is not
     * automatically a better one for a given task, so both may be worth keeping.
     */
    fun diff(capability: Capability): List<ModelUpdate> {
        val installedByFamily = installed()
            .map { it.model }
            .filter { it.supports(capability) }
            .groupBy { it.family }

        return available()
            .map { it.model }
            .filter { it.supports(capability) }
            .flatMap { candidate ->
                val installedInFamily = installedByFamily[candidate.family].orEmpty()
                installedInFamily
                    .filter { isNewer(candidate.version, it.version) }
                    .map { current ->
                        ModelUpdate(
                            installed = current,
                            candidate = candidate,
                            qualityDelta = (candidate.benchmarks.scoreFor(capability) ?: 0.0) -
                                (current.benchmarks.scoreFor(capability) ?: 0.0),
                            speedDelta = candidate.peakReferenceTps() - current.peakReferenceTps(),
                            ramDelta = candidate.minRequiredRam() - current.minRequiredRam(),
                        )
                    }
            }
    }

    /**
     * Numeric-segment version comparison: `3.10` is newer than `3.9`, which
     * plain string ordering gets backwards. Trailing labels ("3.5-instruct")
     * are compared as text once the numbers tie.
     */
    private fun isNewer(candidate: String, current: String): Boolean {
        val a = versionSegments(candidate)
        val b = versionSegments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrNull(i)
            val right = b.getOrNull(i)
            if (left == right) continue
            if (left is Long && right is Long) return left > right
            if (left == null) return false
            if (right == null) return true
            return left.toString() > right.toString()
        }
        return false
    }

    private fun versionSegments(version: String): List<Any> =
        version.split('.', '-', '_', ' ')
            .filter { it.isNotBlank() }
            .map { segment -> segment.toLongOrNull() ?: segment }

    private fun ModelDescriptor.peakReferenceTps(): Double =
        bindings.mapNotNull { it.referenceTokensPerSecond }.maxOrNull() ?: 0.0

    private fun ModelDescriptor.minRequiredRam(): Long =
        bindings.minOf { it.requiredRamBytes }
}
