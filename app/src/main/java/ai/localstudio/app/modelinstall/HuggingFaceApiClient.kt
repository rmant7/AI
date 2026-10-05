package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.HuggingFaceMetadata
import ai.localstudio.model.install.HuggingFaceSearch
import ai.localstudio.model.install.ModelSearchQuery
import ai.localstudio.model.install.RepoSummary
import ai.localstudio.model.install.RepoFile
import ai.localstudio.model.install.SourceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder

/**
 * [HuggingFaceMetadata] over the Hugging Face Hub HTTP API:
 * - `GET /api/models/{repo}/revision/{revision}` → `sha`, the commit;
 * - `GET /api/models/{repo}/tree/{commit}/{directory}` (non-recursive) →
 *   files with `size`, and for LFS files `lfs.size` (the real size — the
 *   top-level `size` is the pointer's) and `lfs.oid` (the file's sha256).
 *
 * - `GET /api/models?filter=...&pipeline_tag=...&search=...&sort=downloads`
 *   → [HuggingFaceSearch]: an array of repositories. This one was written
 *   without a live response to check against (no network to the Hub from
 *   where it was written), so it is read leniently -- `id` or `modelId`,
 *   `gated` as a boolean or a string -- and [lastSearchShape] records what
 *   actually came back, for the app log to show.
 *
 * [apiBase] is configurable for the on-device test harness only.
 * [token]: the user's Hugging Face token, sent only to [apiBase]'s host.
 */
class HuggingFaceApiClient(
    private val apiBase: String = "https://huggingface.co",
    private val token: () -> String? = { null },
) : HuggingFaceMetadata, HuggingFaceSearch {

    /** What the last [searchModels] response looked like: the URL, the item count and the first item's keys (or the start of a non-array body). */
    @Volatile
    var lastSearchShape: String? = null
        private set

    private val json = Json { ignoreUnknownKeys = true }
    private val apiHost = URI.create(apiBase).host

    override fun resolveCommit(repo: String, revision: String): String {
        val body = get("$apiBase/api/models/$repo/revision/${encode(revision)}")
        val sha = ((json.parseToJsonElement(body) as? JsonObject)?.get("sha") as? JsonPrimitive)?.content
        if (sha == null || !COMMIT.matches(sha)) throw SourceException(SourceException.Kind.OTHER, "no commit sha for $repo@$revision")
        return sha
    }

    override fun listFiles(repo: String, commit: String, directory: String): List<RepoFile> {
        val path = directory.split('/').filter { it.isNotEmpty() }.joinToString("/") { encode(it) }
        val url = "$apiBase/api/models/$repo/tree/$commit" + (if (path.isEmpty()) "" else "/$path") + "?recursive=false"
        val entries = json.parseToJsonElement(get(url)) as? JsonArray
            ?: throw SourceException(SourceException.Kind.OTHER, "unexpected tree listing for $repo")
        return entries.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if ((item["type"] as? JsonPrimitive)?.content != "file") return@mapNotNull null
            val filePath = (item["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val lfs = item["lfs"] as? JsonObject
            val size = (lfs?.get("size") as? JsonPrimitive)?.longOrNull ?: (item["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            // lfs.oid is the sha256 of the content; a non-LFS file's oid is a git blob sha1 — not usable.
            val sha256 = (lfs?.get("oid") as? JsonPrimitive)?.content?.lowercase()?.takeIf { SHA256.matches(it) }
            RepoFile(filePath, size, sha256)
        }
    }

    override fun searchModels(query: ModelSearchQuery): List<RepoSummary> {
        val params = buildList {
            query.tags.forEach { add("filter" to it) }
            query.pipelineTag?.let { add("pipeline_tag" to it) }
            query.search?.let { add("search" to it) }
            add("sort" to query.sort.apiValue)
            add("direction" to "-1")
            add("limit" to query.limit.toString())
        }
        val url = "$apiBase/api/models?" + params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val body = get(url)
        val items = json.parseToJsonElement(body) as? JsonArray
        if (items == null) {
            lastSearchShape = "$url -> not an array: ${body.take(300)}"
            throw SourceException(SourceException.Kind.OTHER, "unexpected search response: ${body.take(120)}")
        }
        lastSearchShape = "$url -> ${items.size} items; first: " +
            ((items.firstOrNull() as? JsonObject)?.keys?.sorted()?.joinToString(",") ?: "none")
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = ((item["id"] ?: item["modelId"]) as? JsonPrimitive)?.content ?: return@mapNotNull null
            // false, "auto"/"manual", or absent/null when the listing doesn't carry it.
            val gated = (item["gated"] as? JsonPrimitive)?.takeUnless { it is JsonNull }
                ?.let { it.booleanOrNull ?: (it.content != "false") } ?: false
            RepoSummary(
                id = id,
                downloads = (item["downloads"] as? JsonPrimitive)?.longOrNull ?: 0,
                likes = (item["likes"] as? JsonPrimitive)?.longOrNull ?: 0,
                tags = (item["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                pipelineTag = (item["pipeline_tag"] as? JsonPrimitive)?.content,
                gated = gated,
                createdAt = (item["createdAt"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
            )
        }
    }

    private fun get(url: String): String {
        val connection = HttpConnections.open(
            url,
            headers = mapOf("Accept" to "application/json"),
            bearer = token(),
            sendBearer = { it.host == apiHost },
        )
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            throw HttpConnections.classify(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        val COMMIT = Regex("^[0-9a-f]{40}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
