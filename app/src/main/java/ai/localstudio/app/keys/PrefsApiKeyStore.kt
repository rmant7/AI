package ai.localstudio.app.keys

import android.content.Context
import ai.localstudio.app.Settings
import ai.localstudio.core.keys.ApiKeyEntry
import ai.localstudio.core.keys.ApiKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class StoredKey(val id: String, val key: String, val cooldownUntilEpochMs: Long = 0L)

@Serializable
private data class KeyPools(val pools: Map<String, List<StoredKey>> = emptyMap())

/**
 * Persists each provider's key pool to a flat JSON file — the same approach
 * [ai.localstudio.app.attach.DocumentStore] uses for attachments, since a
 * provider's pool is an ordered list, which SharedPreferences has no native
 * way to store.
 *
 * A provider that has never used the pool feature has no entry here at all;
 * [load] transparently seeds a one-key pool from the legacy single
 * [Settings.apiKeyFor] value the first time it's asked, so someone who typed
 * a key into Settings before this feature existed keeps working with no
 * migration step of their own — that one key just becomes the first (and, if
 * they add no more, only) entry in the pool.
 */
class PrefsApiKeyStore(context: Context, private val settings: Settings) : ApiKeyStore {

    private val file = File(context.filesDir, "api-key-pools.json")
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(providerId: String): List<ApiKeyEntry> {
        val pools = readPools()
        val stored = pools[providerId]
        if (stored != null) return stored.map { ApiKeyEntry(it.id, it.key, it.cooldownUntilEpochMs) }

        val legacy = settings.apiKeyFor(providerId)
        if (legacy.isBlank()) return emptyList()
        val seeded = listOf(ApiKeyEntry(id = UUID.randomUUID().toString(), key = legacy))
        save(providerId, seeded)
        return seeded
    }

    override fun save(providerId: String, entries: List<ApiKeyEntry>) {
        val pools = readPools().toMutableMap()
        pools[providerId] = entries.map { StoredKey(it.id, it.key, it.cooldownUntilEpochMs) }
        writePools(pools)
    }

    private fun readPools(): Map<String, List<StoredKey>> = runCatching {
        if (!file.isFile) return emptyMap()
        json.decodeFromString(KeyPools.serializer(), file.readText()).pools
    }.getOrElse { emptyMap() }

    private fun writePools(pools: Map<String, List<StoredKey>>) {
        file.writeText(json.encodeToString(KeyPools.serializer(), KeyPools(pools)))
    }
}
