package ai.localstudio.app.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SeedStatus(
    val seedId: String,
    val ok: Boolean,
    val detail: String? = null,
    val checkedAt: Long = 0L,
)

@Serializable
private data class StatusFile(val items: List<SeedStatus> = emptyList())

/**
 * Re-validates every catalogue entry against Hugging Face once per app
 * launch (throttled so relaunching within the hour does not repeat it), so a
 * repository that got renamed or pulled shows up as a warning on the Models
 * screen instead of only failing once the user has already tapped Download —
 * which is exactly how "gemini-3.1-flash: 404" happened, just for local
 * models instead of a cloud one.
 *
 * Deliberately reuses [HuggingFaceResolver] rather than a second, unverified
 * way of asking "does this exist": the same code path a real download takes.
 */
class CatalogFreshness(context: Context) {

    private val file = File(context.filesDir, "catalog_freshness.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun cached(): Map<String, SeedStatus> = runCatching {
        file.takeIf { it.isFile }
            ?.let { json.decodeFromString(StatusFile.serializer(), it.readText()) }
    }.getOrNull()?.items?.associateBy { it.seedId }.orEmpty()

    /** Blocking network calls — always call from a background thread. */
    fun refreshIfStale(seeds: List<LocalModelSeed>, token: String?) {
        val now = System.currentTimeMillis()
        val lastCheckedAt = cached().values.maxOfOrNull { it.checkedAt } ?: 0L
        if (now - lastCheckedAt < MIN_INTERVAL_MS) return

        val statuses = mutableListOf<SeedStatus>()
        for (seed in seeds) {
            val error = runCatching { HuggingFaceResolver.resolveAny(seed.repoIds, token) }.exceptionOrNull()
            if (error != null && error.message.orEmpty().contains(NO_NETWORK_MARKER)) {
                // Offline, not broken: every remaining seed would fail the
                // same way for the same reason, so leave the cache as it was
                // rather than recording the whole catalogue as unavailable.
                return
            }
            statuses += SeedStatus(seed.id, ok = error == null, detail = error?.message, checkedAt = now)
        }
        runCatching {
            file.writeText(json.encodeToString(StatusFile.serializer(), StatusFile(statuses)))
        }
    }

    private companion object {
        const val MIN_INTERVAL_MS = 60 * 60 * 1000L
        const val NO_NETWORK_MARKER = "No connection"
    }
}
