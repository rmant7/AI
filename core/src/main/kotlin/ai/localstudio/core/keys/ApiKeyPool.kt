package ai.localstudio.core.keys

/**
 * One API key in a provider's pool.
 *
 * [id] is stable identity independent of the key text itself, so a key can be
 * deleted/re-added without disturbing another entry's [cooldownUntilEpochMs],
 * and so the key value never has to round-trip through UI code that only
 * needs to say "delete this one" or "this one is cooling down".
 */
data class ApiKeyEntry(
    val id: String,
    val key: String,
    /** 0 while usable. Otherwise a key that hit a rate-limit/quota error until this epoch millis. */
    val cooldownUntilEpochMs: Long = 0L,
)

/** Where a provider's key pool actually lives — SharedPreferences on Android, an in-memory map in tests. */
interface ApiKeyStore {
    fun load(providerId: String): List<ApiKeyEntry>
    fun save(providerId: String, entries: List<ApiKeyEntry>)
}

/** [ApiKeyStore] backed by nothing but a map — for tests, and anywhere a real store is not yet wired up. */
class InMemoryApiKeyStore : ApiKeyStore {
    private val pools = mutableMapOf<String, List<ApiKeyEntry>>()

    override fun load(providerId: String): List<ApiKeyEntry> = pools[providerId].orEmpty()

    override fun save(providerId: String, entries: List<ApiKeyEntry>) {
        pools[providerId] = entries
    }
}

/**
 * Picks which of a provider's keys to use next, and remembers — across
 * process restarts, since persistence is [ApiKeyStore]'s job — which ones are
 * serving a rate-limit/quota cooldown so they are not retried too soon.
 *
 * Deliberately carries no separate "current index": the next key is simply
 * the first one in the pool that is not on cooldown right now, so a key that
 * gets exhausted falls out of rotation and whichever key is next in the list
 * takes over on the very next call — nothing to keep in sync, and restarting
 * the app does not forget a cooldown the way an in-memory index would.
 */
class ApiKeyRotator(
    private val store: ApiKeyStore,
    private val providerId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The key to use right now, or null when the pool is empty or every key is cooling down. */
    fun activeKey(): ApiKeyEntry? {
        val now = clock()
        return store.load(providerId).firstOrNull { it.cooldownUntilEpochMs <= now }
    }

    /** Call after an HTTP 429 (rate limit or daily quota exceeded) using this key. */
    fun markExhausted(keyId: String, cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
        val until = clock() + cooldownMs
        val pool = store.load(providerId)
        store.save(providerId, pool.map { if (it.id == keyId) it.copy(cooldownUntilEpochMs = until) else it })
    }

    fun pool(): List<ApiKeyEntry> = store.load(providerId)

    fun poolSize(): Int = pool().size

    fun add(key: String): ApiKeyEntry {
        val entry = ApiKeyEntry(id = java.util.UUID.randomUUID().toString(), key = key.trim())
        store.save(providerId, pool() + entry)
        return entry
    }

    fun remove(keyId: String) {
        store.save(providerId, pool().filterNot { it.id == keyId })
    }

    /**
     * Human-readable reason every key is currently unusable, or null while at
     * least one is not on cooldown. A pool with no keys at all is reported as
     * usable (null) — an empty pool means "no pool configured yet", which the
     * caller is expected to handle by falling back to some other key source,
     * not by treating it as exhaustion.
     */
    fun exhaustionMessage(): String? {
        val pool = pool()
        if (pool.isEmpty()) return null
        val now = clock()
        if (pool.any { it.cooldownUntilEpochMs <= now }) return null
        val minutesLeft = ((pool.minOf { it.cooldownUntilEpochMs } - now) / 60_000L).coerceAtLeast(0)
        return "all ${pool.size} key(s) have hit the daily limit; the next one frees up in ~$minutesLeft min"
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 24 * 60 * 60 * 1000L
    }
}
