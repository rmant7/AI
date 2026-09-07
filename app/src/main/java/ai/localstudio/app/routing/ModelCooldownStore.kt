package ai.localstudio.app.routing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class CooldownEntry(val providerId: String, val modelName: String, val untilEpochMs: Long)

@Serializable
private data class CooldownList(val entries: List<CooldownEntry> = emptyList())

/**
 * Which (provider, model) pairs are temporarily not worth trying — the same
 * idea as the API key pool's cooldown, but for the model itself rather than
 * the key. "HTTP 503: this model is currently experiencing high demand"
 * says nothing about the key, so key rotation cannot route around it, but
 * retrying the exact same overloaded model on every single message wastes
 * 15-20+ seconds per reply for as long as it stays down.
 */
class ModelCooldownStore(context: Context) {

    private val file = File(context.filesDir, "model-cooldowns.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun isOnCooldown(providerId: String, modelName: String): Boolean =
        cooldownUntil(providerId, modelName) > System.currentTimeMillis()

    fun cooldownUntil(providerId: String, modelName: String): Long =
        readAll().firstOrNull { it.providerId == providerId && it.modelName == modelName }?.untilEpochMs ?: 0L

    fun markOverloaded(providerId: String, modelName: String, durationMs: Long = DEFAULT_COOLDOWN_MS) {
        val until = System.currentTimeMillis() + durationMs
        val entries = readAll().filterNot { it.providerId == providerId && it.modelName == modelName } +
            CooldownEntry(providerId, modelName, until)
        writeAll(entries)
    }

    private fun readAll(): List<CooldownEntry> = runCatching {
        if (!file.isFile) return emptyList()
        json.decodeFromString(CooldownList.serializer(), file.readText()).entries
    }.getOrElse { emptyList() }

    private fun writeAll(entries: List<CooldownEntry>) {
        file.writeText(json.encodeToString(CooldownList.serializer(), CooldownList(entries)))
    }

    companion object {
        // A couple of hours: long enough that hammering an overloaded model
        // on every message actually stops, short enough that a model back up
        // within the hour isn't skipped for the rest of the day.
        const val DEFAULT_COOLDOWN_MS: Long = 2 * 60 * 60 * 1000L
    }
}
