package ai.localstudio.model.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The latest device check of each model's bytes, by [ArtifactId.key] --
 * whatever the model is: a discovery candidate, one shipped in the catalog,
 * one the user added. A check belongs to the bytes, not to the screen it was
 * started from, so a model found by discovery and then moved into the user's
 * models keeps it, and a built-in model gets one the same way.
 *
 * Only the latest per artifact is kept: an older one is either the same
 * evidence or evidence for a context that no longer holds.
 */
class VerificationStore(private val file: File) {
    @Serializable
    private data class Stored(val schema: Int = 1, val checks: Map<String, DeviceVerification> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** This store is the file's only writer in the process: read once, then kept (lists ask on every redraw). */
    private var cache: Stored? = null

    @Synchronized
    fun latest(artifact: ArtifactId): DeviceVerification? = read().checks[artifact.key]

    @Synchronized
    fun all(): Map<String, DeviceVerification> = read().checks

    /** Keeps [verification] as its artifact's latest unless a newer one is already stored. False for a record with no artifact. */
    @Synchronized
    fun record(verification: DeviceVerification): Boolean {
        val key = verification.artifact?.key ?: return false
        val stored = read()
        val existing = stored.checks[key]
        if (existing != null && existing.verifiedAtEpochMs > verification.verifiedAtEpochMs) return true
        write(stored.copy(checks = stored.checks + (key to verification)))
        return true
    }

    @Synchronized
    fun forget(artifact: ArtifactId): Boolean {
        val stored = read()
        if (artifact.key !in stored.checks) return false
        write(stored.copy(checks = stored.checks - artifact.key))
        return true
    }

    private fun read(): Stored = cache ?: (
        runCatching { file.takeIf { it.isFile }?.let { json.decodeFromString(Stored.serializer(), it.readText()) } }.getOrNull() ?: Stored()
        ).also { cache = it }

    private fun write(stored: Stored) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(Stored.serializer(), stored))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not write ${file.path}" }
        }
        cache = stored
    }
}
