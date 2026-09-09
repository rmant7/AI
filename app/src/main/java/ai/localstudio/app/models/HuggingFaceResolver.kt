package ai.localstudio.app.models

import ai.localstudio.core.registry.ArtifactResolver
import ai.localstudio.core.registry.RemoteArtifact
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.UnknownHostException

data class ResolvedModelFile(
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/**
 * Turns a repository id into a concrete downloadable GGUF, live.
 *
 * Resolving at runtime rather than at build time is what keeps the catalogue
 * pointing at what a repository actually contains today: file names, offered
 * quantisations, even which repo is canonical for a family all change on a
 * timescale of weeks.
 */
object HuggingFaceResolver {

    private const val API = "https://huggingface.co/api/models"
    private const val RESOLVE_RETRIES = 3
    private const val RESOLVE_RETRY_DELAY_MS = 2_000L

    /**
     * Tries each repository in turn and reports every failure if none works.
     *
     * A single source is a single point of failure: gated repositories answer
     * 401/403 without a token, and any repository can be renamed. The error
     * message lists what each source said, because "не удалось скачать" is not
     * something a user can act on.
     */
    fun resolveAny(repoIds: List<String>, token: String? = null): Pair<String, ResolvedModelFile> {
        val failures = mutableListOf<String>()
        for (repoId in repoIds) {
            try {
                return repoId to resolveWithRetry(repoId, token)
            } catch (e: NoNetworkException) {
                // Every repoId lives on the same host: a DNS failure on the
                // first one will repeat identically for the rest. Trying them
                // anyway just produces the same line four times and burns the
                // retry budget on a problem that is not about the repository.
                throw IOException("No connection to huggingface.co — check your internet. (${e.message})")
            } catch (e: Exception) {
                failures += "$repoId: ${e.message}"
            }
        }
        throw IOException("No source worked.\n" + failures.joinToString("\n"))
    }

    private class NoNetworkException(message: String) : IOException(message)

    /**
     * A DNS hiccup while switching between Wi-Fi and mobile data is common and
     * transient; [resolve] itself is not retried by the caller the way the
     * download body is, so without this a blip here fails the whole model
     * instead of a brief pause.
     */
    private fun resolveWithRetry(repoId: String, token: String?): ResolvedModelFile {
        var lastNetworkError: UnknownHostException? = null
        repeat(RESOLVE_RETRIES) { attempt ->
            try {
                return resolve(repoId, token)
            } catch (e: UnknownHostException) {
                lastNetworkError = e
                if (attempt < RESOLVE_RETRIES - 1) Thread.sleep(RESOLVE_RETRY_DELAY_MS)
            }
        }
        throw NoNetworkException(lastNetworkError?.message ?: "host unreachable")
    }

    fun resolve(repoId: String, token: String? = null): ResolvedModelFile {
        val entries = fetchTree(repoId, token)
        val best = ArtifactResolver.pickBest(entries)
            ?: throw IOException("no suitable .gguf file (the model may be split into parts)")
        return ResolvedModelFile(
            fileName = best.path.substringAfterLast('/'),
            sizeBytes = best.sizeBytes,
            downloadUrl = "https://huggingface.co/$repoId/resolve/main/${best.path}",
        )
    }

    /**
     * Looks for one exact file name across a list of repos — used for a
     * vision projector (mmproj), which carries no quant tag [ArtifactResolver]
     * could match on and would otherwise have to be guessed at.
     *
     * Returns null rather than throwing on a total miss: a projector is a
     * bonus, not a requirement, so a missing or renamed one should leave the
     * model itself installable and usable, just without vision — never fail
     * the whole download the way [resolveAny] does for the main GGUF.
     */
    fun resolveExact(repoIds: List<String>, fileName: String, token: String? = null): Pair<String, ResolvedModelFile>? {
        for (repoId in repoIds) {
            val match = runCatching { fetchTree(repoId, token) }
                .getOrNull()
                ?.firstOrNull { it.path.substringAfterLast('/') == fileName }
                ?: continue
            return repoId to ResolvedModelFile(
                fileName = fileName,
                sizeBytes = match.sizeBytes,
                downloadUrl = "https://huggingface.co/$repoId/resolve/main/${match.path}",
            )
        }
        return null
    }

    private fun fetchTree(repoId: String, token: String?): List<RemoteArtifact> {
        val connection = (URI.create("$API/$repoId/tree/main?recursive=false").toURL()
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LocalAiStudio/0.1 (Android)")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val status = connection.responseCode
            if (status == 404) throw IOException("repository not found")
            if (status == 401 || status == 403) {
                throw IOException(
                    "access denied (HTTP $status) — this repository is gated: it needs a Hugging Face " +
                        "token and an accepted license",
                )
            }
            if (status !in 200..299) throw IOException("Hugging Face responded with HTTP $status")
            return parseTree(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The real size of a large file lives under `lfs.size`; the top-level
     * `size` is the size of the LFS pointer. Reading the wrong one produces a
     * catalogue of 130-byte models.
     */
    internal fun parseTree(body: String): List<RemoteArtifact> {
        val array = JSONArray(body)
        val entries = mutableListOf<RemoteArtifact>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val path = item.optString("path", "")
            if (path.isBlank()) continue
            val lfsSize = item.optJSONObject("lfs")?.optLong("size", -1) ?: -1
            entries += RemoteArtifact(path, if (lfsSize >= 0) lfsSize else item.optLong("size", 0))
        }
        return entries
    }
}
