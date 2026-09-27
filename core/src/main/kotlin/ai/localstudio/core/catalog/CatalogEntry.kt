package ai.localstudio.core.catalog

import ai.localstudio.core.capability.Capability
import kotlinx.serialization.Serializable

/**
 * What using [CatalogEntry.status] for routing actually means — see
 * [ModelCatalog.merge]'s own doc comment for how an entry gets from one to
 * another. A provider's own `/models` response ([ai.localstudio.core.provider.DiscoveredModel])
 * is never, on its own, enough to call a model VERIFIED: knowing a model
 * exists says nothing about whether it's actually good, so promoting to
 * VERIFIED only ever comes from this app's own catalog (bundled or remote),
 * never from discovery.
 */
@Serializable
enum class ModelStatus {
    /** A provider reports it; not in this app's own catalog. Manual selection only, never Auto. */
    DISCOVERED,

    /** In this app's own versioned catalog and allowed for Auto (capability-based) selection. */
    VERIFIED,

    /** In this app's own catalog, but not allowed for Auto — a preview/experimental release, say. */
    EXPERIMENTAL,

    /** Must not be used, even if the provider still lists it. Wins over every other signal. */
    DEPRECATED,

    /** Known to this app, but not currently offered — the provider's own discovery no longer lists it. */
    UNAVAILABLE,
}

/**
 * One (provider, model) pair's normalized metadata — what
 * [ai.localstudio.core.provider.DiscoveredModel] doesn't carry (a provider's
 * `/models` response varies wildly in how much it reports, see that class's
 * own doc comment) plus this app's own editorial judgment ([status]).
 *
 * [capabilities]/[contextWindow] are what this app's own catalog says a
 * model can do — not read from the provider's response, since most of the
 * providers this app targets don't report either at all.
 */
@Serializable
data class CatalogEntry(
    val providerId: String,
    val modelId: String,
    val status: ModelStatus,
    val capabilities: Set<Capability> = emptySet(),
    val contextWindow: Int? = null,
    val displayName: String? = null,
    val notes: String? = null,
)
