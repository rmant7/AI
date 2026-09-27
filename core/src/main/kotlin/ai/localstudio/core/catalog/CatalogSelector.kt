package ai.localstudio.core.catalog

import ai.localstudio.core.capability.Capability

/**
 * Picks a model from a merged catalog (see [ModelCatalog.merge]) — the
 * capability-based selection docs/17-ai-core-stage1.md's session 11
 * describes: "discover → normalize → verify → select," with manual
 * selection still working regardless of what Auto would have picked.
 */
object CatalogSelector {

    /** The only statuses Auto selection ever considers — see [CatalogEntry.status]'s own doc comment on why. */
    private val AUTO_ELIGIBLE_STATUSES = setOf(ModelStatus.VERIFIED)

    /**
     * The best VERIFIED entry for [capability], among [enabledProviderIds]
     * only. [providerPriority] breaks a tie between providers that both
     * have an eligible entry — normally the same order a caller's fallback
     * chain already tries providers in (local first, then each enabled
     * cloud provider); providers it doesn't mention are considered after
     * every one it does, in the catalog's own order. Null when nothing
     * eligible exists at all — the caller falls through to whatever comes
     * after Auto selection today (nothing this function decides).
     */
    fun selectAuto(
        catalog: List<CatalogEntry>,
        capability: Capability,
        enabledProviderIds: Set<String>,
        providerPriority: List<String> = emptyList(),
    ): CatalogEntry? {
        val eligible = catalog.filter {
            it.providerId in enabledProviderIds && it.status in AUTO_ELIGIBLE_STATUSES && capability in it.capabilities
        }
        if (eligible.isEmpty()) return null
        for (providerId in providerPriority) {
            eligible.firstOrNull { it.providerId == providerId }?.let { return it }
        }
        return eligible.first()
    }

    /**
     * A user's own explicit (provider, model) choice — allowed regardless
     * of [CatalogEntry.status], including DEPRECATED/EXPERIMENTAL/
     * UNAVAILABLE/DISCOVERED: someone who typed in a specific model id
     * (an editable model field, same as this app's own [CatalogEntry]-less
     * "editable model" fields already work today) knows something the
     * catalog doesn't, or is deliberately trying something experimental.
     * Still requires [providerId] to be in [enabledProviderIds] — a
     * disabled provider stays disabled regardless of what's typed into its
     * model field. Null when the pair isn't in the catalog at all: the
     * caller decides what "unknown model, try it anyway" means, same as it
     * does today for a model this app's catalog has simply never heard of.
     */
    fun selectManual(
        catalog: List<CatalogEntry>,
        providerId: String,
        modelId: String,
        enabledProviderIds: Set<String>,
    ): CatalogEntry? {
        if (providerId !in enabledProviderIds) return null
        return catalog.firstOrNull { it.providerId == providerId && it.modelId == modelId }
    }
}
