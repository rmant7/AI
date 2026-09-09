package ai.localstudio.app.keys

import ai.localstudio.app.BuildConfig
import ai.localstudio.core.keys.ApiKeyEntry
import ai.localstudio.core.keys.ApiKeyStore
import java.util.UUID

/**
 * Keys baked into this build via [BuildConfig] — sourced from GitHub Actions
 * secrets at CI build time (see app/build.gradle.kts and
 * .github/workflows/android.yml), never committed to the repo — so a fresh
 * install has a working cloud fallback with nothing for the user to type in.
 * [ApiKeyRotator] only ever reaches for one of these once the user's own
 * pool has nothing usable; see its own doc comment on that ordering.
 */
object BundledApiKeys {

    private fun encodedFor(providerId: String): String = when (providerId) {
        "groq" -> BuildConfig.GROQ_BUNDLED_KEYS
        "gemini" -> BuildConfig.GEMINI_BUNDLED_KEYS
        else -> ""
    }

    /** Base64-decoded on this side — see app/build.gradle.kts' own comment on why it's encoded at all. */
    fun forProvider(providerId: String): List<String> {
        val encoded = encodedFor(providerId)
        if (encoded.isBlank()) return emptyList()
        val decoded = runCatching {
            String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault("")
        return decoded.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Reconciles [store]'s pool for [providerId] against the compiled-in key
     * list: adds newly-bundled keys, drops ones no longer bundled, and —
     * this is the part that matters — leaves an unchanged key's cooldown
     * exactly as it was, so a key already serving a rate-limit pause from
     * earlier today does not get retried immediately just because the app
     * restarted. Cheap to call on every launch: a no-op once the store
     * already matches.
     */
    fun sync(store: ApiKeyStore, providerId: String) {
        val bundled = forProvider(providerId)
        val existing = store.load(providerId)
        if (bundled.isEmpty()) {
            if (existing.isNotEmpty()) store.save(providerId, emptyList())
            return
        }
        val reconciled = bundled.map { key ->
            existing.firstOrNull { it.key == key } ?: ApiKeyEntry(id = UUID.randomUUID().toString(), key = key)
        }
        if (reconciled != existing) store.save(providerId, reconciled)
    }
}
