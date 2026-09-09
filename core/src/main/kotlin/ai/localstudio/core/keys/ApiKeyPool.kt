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
    /**
     * A second, separate pool for keys bundled into the build itself — only
     * ever reached for once [store]'s own pool has nothing usable right now,
     * so a key the user added always wins. Kept in a store of its own rather
     * than merged into [store]'s: these never came from the user, so they
     * have no business appearing in a UI that lists what the user typed in
     * and lets them delete it.
     */
    private val bundledStore: ApiKeyStore? = null,
) {

    /** The key to use right now, or null when both pools are empty or every key is cooling down. */
    fun activeKey(): ApiKeyEntry? {
        val now = clock()
        store.load(providerId).firstOrNull { it.cooldownUntilEpochMs <= now }?.let { return it }
        return bundledStore?.load(providerId)?.firstOrNull { it.cooldownUntilEpochMs <= now }
    }

    /** Call after an HTTP 429 (rate limit or daily quota exceeded) using this key. */
    fun markExhausted(keyId: String, cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
        val until = clock() + cooldownMs
        val pool = store.load(providerId)
        if (pool.any { it.id == keyId }) {
            store.save(providerId, pool.map { if (it.id == keyId) it.copy(cooldownUntilEpochMs = until) else it })
            return
        }
        val bundled = bundledStore ?: return
        val bundledPool = bundled.load(providerId)
        if (bundledPool.any { it.id == keyId }) {
            bundled.save(providerId, bundledPool.map { if (it.id == keyId) it.copy(cooldownUntilEpochMs = until) else it })
        }
    }

    fun pool(): List<ApiKeyEntry> = store.load(providerId)

    fun poolSize(): Int = pool().size

    /**
     * Whether there is a configured pool at all — the user's own, the
     * bundled one, or both — regardless of whether anything in it is
     * currently on cooldown. Callers use this to decide whether key rotation
     * applies at all versus falling through to some other, keyless path;
     * [poolSize] alone would miss a bundled-only pool with no user keys.
     */
    fun hasAnyKey(): Boolean = pool().isNotEmpty() || bundledStore?.load(providerId)?.isNotEmpty() == true

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
     *
     * Considers both pools, same as [activeKey] and [hasAnyKey] — checking
     * only [pool] here made this report nothing useful for the exact case
     * [hasAnyKey] exists for: a user with no key of their own, running
     * entirely on a bundled one. That combination produced the single most
     * confusing failure this app can show — "No available API keys for this
     * provider" while a bundled key visibly existed and was simply cooling
     * down from a rate limit — instead of the real reason and an ETA.
     */
    fun exhaustionMessage(): String? {
        val combined = pool() + bundledStore?.load(providerId).orEmpty()
        if (combined.isEmpty()) return null
        val now = clock()
        if (combined.any { it.cooldownUntilEpochMs <= now }) return null
        val minutesLeft = ((combined.minOf { it.cooldownUntilEpochMs } - now) / 60_000L).coerceAtLeast(0)
        return "all ${combined.size} key(s) have hit the daily limit; the next one frees up in ~$minutesLeft min"
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS: Long = 24 * 60 * 60 * 1000L
    }
}
