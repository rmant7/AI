package ai.localstudio.core.catalog

import ai.localstudio.core.provider.DiscoveredModel

/**
 * Merges the three sources docs/17-ai-core-stage1.md's catalog design
 * names — a bundled repo catalog (offline fallback), a remote catalog (same
 * JSON shape, fetched from this repo without an APK release), and live
 * provider discovery — into one list of [CatalogEntry]. A pure function,
 * not a stateful store: every call is a fresh merge from whatever the three
 * inputs currently say, not a progressive upgrade of some previously merged
 * result — a caller re-merges whenever any input changes (a new remote
 * fetch, a fresh discovery pass), rather than this class remembering
 * anything between calls.
 */
object ModelCatalog {

    /**
     * @param repoEntries This app's own bundled catalog — always present, the offline fallback.
     * @param remoteEntries The same shape, fetched over the network; wins over [repoEntries] for
     *   the same (provider, model) key, including its [CatalogEntry.status] — but only for a
     *   provider already in [knownProviderIds]. A remote catalog describes models of *existing*
     *   providers only; it can never introduce a provider, base URL, or credential this app
     *   doesn't already have configured — that boundary is what keeps a compromised or malicious
     *   remote file from doing anything worse than mis-describing a model this app already talks to.
     * @param discoveredByProvider This run's own [ai.localstudio.core.provider.AIProvider.discoverModels]
     *   results, keyed by provider id. Omit a provider entirely (don't pass an empty list for it)
     *   when its discovery call itself failed — an empty list here means "this provider genuinely
     *   has zero models right now," which marks every one of its known entries [ModelStatus.UNAVAILABLE].
     * @param knownProviderIds Every provider id this app actually has configured — the boundary
     *   both [remoteEntries] and [discoveredByProvider] are checked against; an entry naming any
     *   other provider id is dropped outright.
     */
    fun merge(
        repoEntries: List<CatalogEntry>,
        remoteEntries: List<CatalogEntry> = emptyList(),
        discoveredByProvider: Map<String, List<DiscoveredModel>> = emptyMap(),
        knownProviderIds: Set<String>,
    ): List<CatalogEntry> {
        val byKey = LinkedHashMap<Pair<String, String>, CatalogEntry>()

        for (entry in repoEntries.filter { it.providerId in knownProviderIds }) {
            byKey[entry.providerId to entry.modelId] = entry
        }
        // Remote replaces the repo entry wholesale for a shared key — including
        // status — rather than merging field by field: a remote entry is meant
        // to be a corrected/updated version of the bundled one, not a patch.
        for (entry in remoteEntries.filter { it.providerId in knownProviderIds }) {
            byKey[entry.providerId to entry.modelId] = entry
        }

        for ((providerId, discovered) in discoveredByProvider) {
            if (providerId !in knownProviderIds) continue
            val discoveredIds = discovered.map { it.id }.toSet()

            // A known entry for this provider the live discovery no longer
            // lists: mark UNAVAILABLE, unless it's DEPRECATED — which wins
            // over "not currently reachable" too, since a deprecated model
            // must not be used even on the rare turn it IS reachable.
            for (key in byKey.keys.filter { it.first == providerId }.toList()) {
                val entry = byKey.getValue(key)
                if (entry.modelId !in discoveredIds && entry.status != ModelStatus.DEPRECATED) {
                    byKey[key] = entry.copy(status = ModelStatus.UNAVAILABLE)
                }
            }

            // A model discovery found that isn't already known at all (from
            // repo or remote): DISCOVERED, never higher — discovery alone
            // never promotes a model to VERIFIED/EXPERIMENTAL, only this
            // app's own catalog does that. A model already known keeps
            // whatever status the repo/remote step above gave it; being
            // present in discoveredIds just means it isn't touched by the
            // UNAVAILABLE step above, not that anything is written for it here.
            for (model in discovered) {
                val key = providerId to model.id
                if (key !in byKey) {
                    byKey[key] = CatalogEntry(providerId, model.id, ModelStatus.DISCOVERED)
                }
            }
        }

        return byKey.values.toList()
    }
}
