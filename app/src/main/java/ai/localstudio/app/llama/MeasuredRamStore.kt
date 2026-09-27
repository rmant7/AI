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
class MeasuredRamStore(context: Context) {

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

    private fun keyFor(artifactPath: String, contextTokens: Int): String? {
        val file = File(artifactPath)
        if (!file.isFile) return null
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return "$canonical|${file.length()}|$contextTokens"
    }

    private fun decode(raw: String?): RamMeasurement? {
        val parts = raw?.split(',') ?: return null
        val peak = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val count = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return runCatching { RamMeasurement(peak, count) }.getOrNull()
    }

    private companion object {
        const val PREFS_NAME = "measured_ram"
    }
}
