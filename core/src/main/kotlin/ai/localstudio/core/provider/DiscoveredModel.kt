package ai.localstudio.core.provider

import kotlinx.serialization.json.JsonObject

/**
 * One entry from a provider's own `GET /models` listing — see
 * [OpenAiModelsListParser]. Deliberately thin: an id, plus what the OpenAI
 * `/models` shape happens to carry for (almost) free. This is raw
 * discovery, the DISCOVERED status from docs/17-ai-core-stage1.md's model
 * catalog design, not a normalized [ai.localstudio.core.registry.ModelDescriptor] —
 * capabilities, context window, and everything else a provider doesn't
 * report here comes from this app's own catalog (sessions 9-10), which
 * matches [id] against entries like this one rather than trusting the
 * provider's own metadata (or lack of it) outright.
 *
 * [raw] keeps the provider's full JSON object for this entry, unparsed —
 * OpenRouter's own `/models` response carries pricing, `context_length` and
 * modality info this shape doesn't name fields for; rather than growing
 * this class (and re-parsing already-fetched data) every time a normalizer
 * wants one more field a specific provider happens to report, the
 * normalizer reads directly from [raw] for anything beyond the common
 * subset every OpenAI-compatible provider is expected to have.
 */
data class DiscoveredModel(
    val id: String,
    val ownedBy: String? = null,
    val createdEpochSeconds: Long? = null,
    val raw: JsonObject,
)
