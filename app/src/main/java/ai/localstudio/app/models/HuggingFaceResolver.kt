package ai.localstudio.app.models

import ai.localstudio.core.registry.ArtifactResolver
import ai.localstudio.core.registry.RemoteArtifact
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

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

    fun resolve(repoId: String): ResolvedModelFile {
        val entries = fetchTree(repoId)
        val best = ArtifactResolver.pickBest(entries)
            ?: throw IOException("В репозитории $repoId нет подходящего файла .gguf")
        return ResolvedModelFile(
            fileName = best.path.substringAfterLast('/'),
            sizeBytes = best.sizeBytes,
            downloadUrl = "https://huggingface.co/$repoId/resolve/main/${best.path}",
        )
    }

    private fun fetchTree(repoId: String): List<RemoteArtifact> {
        val connection = (URI.create("$API/$repoId/tree/main").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            if (status == 404) throw IOException("Репозиторий $repoId не найден")
            if (status !in 200..299) throw IOException("Hugging Face ответил HTTP $status для $repoId")
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
