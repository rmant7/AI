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
private data class BundledKeyPools(
    val pools: Map<String, List<StoredBundledKey>> = emptyMap(),
    /** See [BundledApiKeyStore.clearStaleCooldownsOnce]. */
    val staleCooldownsCleared: Boolean = false,
)

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
        val current = readAll()
        val pools = current.pools.toMutableMap()
        pools[providerId] = entries.map { StoredBundledKey(it.id, it.key, it.cooldownUntilEpochMs) }
        writeAll(current.copy(pools = pools))
    }

    /**
     * One-time migration: every cooldown stored before this fix shipped was
     * set by markExhausted()'s old unconditional 24h default, whether the
     * 429 that triggered it was a real daily-quota exhaustion or (far more
     * likely, on a free-tier pool with exactly one key) an ordinary
     * per-minute burst — see OpenAiRuntime's retryAfterMs. An install that
     * already hit that bug is stuck on an artificially long wait that this
     * fix's improved logic would never have set in the first place, and
     * with no user-facing way to clear it (bundled keys are deliberately
     * invisible to ApiKeysActivity). Clearing every cooldown exactly once
     * self-heals that: worst case, a key that really is still exhausted
     * gets one wasted retry and a fresh, correctly-computed cooldown from
     * the response that follows; best case, a key blocked for up to a day
     * over a transient burst is usable again immediately. Guarded by a
     * persisted flag so this never fires a second time and starts undoing
     * legitimate cooldowns the fixed logic sets from here on.
     */
    fun clearStaleCooldownsOnce() {
        val current = readAll()
        if (current.staleCooldownsCleared) return
        val cleared = current.pools.mapValues { (_, keys) -> keys.map { it.copy(cooldownUntilEpochMs = 0L) } }
        writeAll(BundledKeyPools(pools = cleared, staleCooldownsCleared = true))
    }

    private fun readAll(): BundledKeyPools = runCatching {
        if (!file.isFile) return BundledKeyPools()
        json.decodeFromString(BundledKeyPools.serializer(), file.readText())
    }.getOrElse { BundledKeyPools() }

    private fun readPools(): Map<String, List<StoredBundledKey>> = readAll().pools

    private fun writeAll(pools: BundledKeyPools) {
        file.writeText(json.encodeToString(BundledKeyPools.serializer(), pools))
    }
}
