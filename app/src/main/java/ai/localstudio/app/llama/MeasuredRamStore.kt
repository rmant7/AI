package ai.localstudio.app.llama

import ai.localstudio.core.registry.RamMeasurement
import android.content.Context
import java.io.File

/**
 * Persists [RamMeasurement]s taken by [RamMeasuringRuntime], keyed by the
 * model file's canonical path, its size and the context size it was loaded
 * with — the size so a re-download that resolved to a different quant never
 * inherits the old file's number, the context size because the KV cache
 * scales with it.
 */
class MeasuredRamStore(
    context: Context,
    /** Whether weights are mapped from their file right now (see Settings.mapModelWeights): each way is measured on its own. */
    private val weightsMapped: () -> Boolean = { true },
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun measurementFor(artifactPath: String, contextTokens: Int?): RamMeasurement? {
        val key = keyFor(artifactPath, contextTokens ?: return null) ?: return null
        return decode(prefs.getString(key, null))
    }

    @Synchronized
    fun record(artifactPath: String, contextTokens: Int, peakBytes: Long): RamMeasurement? {
        val key = keyFor(artifactPath, contextTokens) ?: return null
        val merged = decode(prefs.getString(key, null))?.merge(peakBytes) ?: RamMeasurement(peakBytes, 1)
        prefs.edit().putString(key, "${merged.peakBytes},${merged.sampleCount}").apply()
        return merged
    }

    /** Drops what was measured for this file and context size; the next run measures from scratch. True when there was something. */
    @Synchronized
    fun forget(artifactPath: String, contextTokens: Int): Boolean {
        val key = keyFor(artifactPath, contextTokens) ?: return false
        if (!prefs.contains(key)) return false
        prefs.edit().remove(key).apply()
        return true
    }

    private fun keyFor(artifactPath: String, contextTokens: Int): String? {
        val file = File(artifactPath)
        if (!file.isFile) return null
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        // KEY_VERSION: measurements from before the projector was loaded
        // lazily include it (~1 GB for Gemma's) — dropped rather than trusted.
        // Mapped and read-into-memory weights cost differently (mapped pages and a repacked copy count twice): never mixed.
        val mode = if (weightsMapped()) "" else "|read"
        return "$KEY_VERSION|$canonical|${file.length()}|$contextTokens$mode"
    }

    private fun decode(raw: String?): RamMeasurement? {
        val parts = raw?.split(',') ?: return null
        val peak = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val count = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return runCatching { RamMeasurement(peak, count) }.getOrNull()
    }

    private companion object {
        const val PREFS_NAME = "measured_ram"
        const val KEY_VERSION = "v2"
    }
}
