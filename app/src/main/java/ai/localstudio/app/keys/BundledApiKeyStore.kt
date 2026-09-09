package ai.localstudio.app.keys

import android.content.Context
import ai.localstudio.core.keys.ApiKeyEntry
import ai.localstudio.core.keys.ApiKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StoredBundledKey(val id: String, val key: String, val cooldownUntilEpochMs: Long = 0L)

@Serializable
private data class BundledKeyPools(val pools: Map<String, List<StoredBundledKey>> = emptyMap())

/**
 * Cooldown state for keys baked into the build itself (see [BundledApiKeys]),
 * in a file of its own rather than [PrefsApiKeyStore]'s: these keys were
 * never typed in by the user and never should appear in ApiKeysActivity's
 * list, which reads and writes that other file — only [ApiKeyRotator]'s own
 * bundled-fallback path ever touches this one.
 */
class BundledApiKeyStore(context: Context) : ApiKeyStore {

    private val file = File(context.filesDir, "bundled-api-key-pools.json")
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(providerId: String): List<ApiKeyEntry> =
        readPools()[providerId]?.map { ApiKeyEntry(it.id, it.key, it.cooldownUntilEpochMs) }.orEmpty()

    override fun save(providerId: String, entries: List<ApiKeyEntry>) {
        val pools = readPools().toMutableMap()
        pools[providerId] = entries.map { StoredBundledKey(it.id, it.key, it.cooldownUntilEpochMs) }
        writePools(pools)
    }

    private fun readPools(): Map<String, List<StoredBundledKey>> = runCatching {
        if (!file.isFile) return emptyMap()
        json.decodeFromString(BundledKeyPools.serializer(), file.readText()).pools
    }.getOrElse { emptyMap() }

    private fun writePools(pools: Map<String, List<StoredBundledKey>>) {
        file.writeText(json.encodeToString(BundledKeyPools.serializer(), BundledKeyPools(pools)))
    }
}
