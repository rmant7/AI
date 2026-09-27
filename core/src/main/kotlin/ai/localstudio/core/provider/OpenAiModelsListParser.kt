package ai.localstudio.core.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses a `GET /models` response body — the one endpoint every
 * OpenAI-compatible provider this app targets (Groq, Gemini's own
 * OpenAI-compatible layer, Mistral, xAI, OpenRouter, a custom
 * `llama-server`/Ollama endpoint) is expected to support, and the same call
 * [ai.localstudio.openai.ApiKeyValidator] already makes to check a key —
 * into provider-agnostic [DiscoveredModel]s.
 *
 * Deliberately not [kotlinx.serialization]'s `@Serializable` data classes:
 * the standard shape is `{"data":[{"id":...,"created":...,"owned_by":...}]}`,
 * but real providers vary (OpenRouter's `created`/`owned_by` aren't always
 * present; some extra fields differ in type across providers) enough that a
 * strict decode either fails on a field a strict schema didn't expect, or
 * has to declare every provider's own variant fields up front. Reading
 * through [JsonObject] accessors instead means one malformed/missing field
 * on one entry drops that entry, not the whole response.
 */
object OpenAiModelsListParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Never throws on a malformed body — returns an empty list instead, same
     * as "this provider's discovery found nothing new" rather than crashing
     * a caller that fetches this in the background. A caller that needs to
     * tell "empty on purpose" from "the response didn't parse at all" should
     * check the body/status itself before calling this.
     */
    fun parse(responseBody: String): List<DiscoveredModel> = runCatching {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val entries = root["data"]?.jsonArray ?: return emptyList()
        entries.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@runCatching null
                DiscoveredModel(
                    id = id,
                    ownedBy = obj["owned_by"]?.jsonPrimitive?.content,
                    createdEpochSeconds = obj["created"]?.jsonPrimitive?.longOrNull,
                    raw = obj,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
