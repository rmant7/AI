package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.HuggingFaceMetadata
import ai.localstudio.model.install.RepoFile
import ai.localstudio.model.install.SourceException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * [apiBase] is configurable for the on-device test harness only.
 * [token]: the user's Hugging Face token, sent only to [apiBase]'s host.
 */
class HuggingFaceApiClient(
    private val apiBase: String = "https://huggingface.co",
    private val token: () -> String? = { null },
) : HuggingFaceMetadata {

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
