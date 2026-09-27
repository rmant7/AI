package ai.localstudio.core.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CatalogFile(val entries: List<CatalogEntry> = emptyList())

/**
 * Parses the one JSON shape both the bundled repo catalog and the remote
 * catalog use — `{"entries": [...]}`, each entry [CatalogEntry]'s own
 * `@Serializable` shape. Unlike [ai.localstudio.core.provider.OpenAiModelsListParser]
 * (a provider's own response, whose shape this app doesn't control),
 * this file's shape is entirely this app's own, so a strict decode is the
 * right default — a typo here is this app's own bug, worth surfacing loudly
 * rather than silently dropping the entry.
 */
object CatalogFileParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** @throws kotlinx.serialization.SerializationException on a malformed file. */
    fun parse(fileContent: String): List<CatalogEntry> = json.decodeFromString(CatalogFile.serializer(), fileContent).entries

    /**
     * Same as [parse], but empty on any failure instead of throwing — for a
     * remote fetch, where a malformed file (a bad edit, a network response
     * that isn't what it should be) must fall back to whatever [merge]'s
     * other inputs (the bundled repo catalog) already provide, not crash a
     * catalog refresh outright.
     */
    fun parseOrEmpty(fileContent: String): List<CatalogEntry> = runCatching { parse(fileContent) }.getOrDefault(emptyList())
}
