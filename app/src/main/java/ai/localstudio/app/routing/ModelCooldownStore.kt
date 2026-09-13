package ai.localstudio.app.routing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class CooldownEntry(
    val providerId: String,
    val modelName: String,
    val untilEpochMs: Long,
    // Defaulted so a cooldown file written by an older build (before this
    // field existed) still decodes — ignoreUnknownKeys covers fields THIS
    // version doesn't know about, not ones it added that an old file lacks.
    val consecutiveFailures: Int = 1,
    val lastFailureAtMs: Long = untilEpochMs,
)

@Serializable
private data class CooldownList(val entries: List<CooldownEntry> = emptyList())

/**
 * Which (provider, model) pairs are temporarily not worth trying — the same
 * idea as the API key pool's cooldown, but for the model itself rather than
 * the key. "HTTP 503: this model is currently experiencing high demand"
 * says nothing about the key, so key rotation cannot route around it, but
 * retrying the exact same overloaded model on every single message wastes
 * 15-20+ seconds per reply for as long as it stays down.
 *
 * The very first 503 a model has seen in a while used to cost it the same
 * flat [DEFAULT_COOLDOWN_MS] as its twentieth in a row — one bad response
 * during a brief demand spike took a model out of rotation for two hours,
 * indistinguishable from a model that is genuinely down for the afternoon.
 * [markOverloaded] now escalates: short the first time, doubling on each
 * consecutive failure up to the same [DEFAULT_COOLDOWN_MS] ceiling as
 * before, and resets back to the short cooldown once a failure arrives long
 * enough after the last one that it reads as a fresh problem rather than a
 * continuation of the same outage.
 */
class ModelCooldownStore(context: Context) {

    private val file = File(context.filesDir, "model-cooldowns.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun isOnCooldown(providerId: String, modelName: String): Boolean =
        cooldownUntil(providerId, modelName) > System.currentTimeMillis()

    fun cooldownUntil(providerId: String, modelName: String): Long =
        readAll().firstOrNull { it.providerId == providerId && it.modelName == modelName }?.untilEpochMs ?: 0L

    fun markOverloaded(providerId: String, modelName: String) {
        val now = System.currentTimeMillis()
        val existing = readAll().firstOrNull { it.providerId == providerId && it.modelName == modelName }
        // A failure arriving well after the previous cooldown already lifted
        // reads as a new problem, not a continuation — a model that was fine
        // for the last several hours doesn't deserve to start right back at
        // its old backoff tier the moment it hiccups once more.
        val isFreshProblem = existing == null || now - existing.lastFailureAtMs > STALE_FAILURE_RESET_MS
        val failures = if (isFreshProblem) 1 else existing!!.consecutiveFailures + 1
        val durationMs = minOf(INITIAL_COOLDOWN_MS shl (failures - 1).coerceAtMost(30), DEFAULT_COOLDOWN_MS)
        val entries = readAll().filterNot { it.providerId == providerId && it.modelName == modelName } +
            CooldownEntry(providerId, modelName, now + durationMs, failures, now)
        writeAll(entries)
    }

    /** How long the cooldown [markOverloaded] just wrote actually runs — for logging. */
    fun currentCooldownMs(providerId: String, modelName: String): Long =
        readAll().firstOrNull { it.providerId == providerId && it.modelName == modelName }
            ?.let { it.untilEpochMs - it.lastFailureAtMs } ?: 0L

    private fun readAll(): List<CooldownEntry> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString(CooldownList.serializer(), file.readText()).entries
    }.getOrElse { emptyList() }

    private fun writeAll(entries: List<CooldownEntry>) {
        file.writeText(json.encodeToString(CooldownList.serializer(), CooldownList(entries)))
    }

    companion object {
        // The ceiling repeated, back-to-back failures still escalate to —
        // long enough that hammering a genuinely-down model on every message
        // actually stops, short enough that a model back up within the hour
        // isn't skipped for the rest of the day.
        const val DEFAULT_COOLDOWN_MS: Long = 2 * 60 * 60 * 1000L

        // What a single, first-time 503 costs a model — short enough that a
        // brief demand spike barely costs the next message anything, unlike
        // the old flat two-hour cooldown every failure paid regardless.
        private const val INITIAL_COOLDOWN_MS: Long = 3 * 60 * 1000L

        // A failure this long after the previous one is treated as unrelated
        // to it — the backoff tier resets instead of continuing to escalate.
        private const val STALE_FAILURE_RESET_MS: Long = DEFAULT_COOLDOWN_MS
    }
}
