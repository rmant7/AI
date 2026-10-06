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
    private val prefs: KeyValueStore,
    /** Whether this model file's weights are mapped from it (see [ai.localstudio.core.runtime.WeightsLoadPolicy]): each way is measured on its own. */
    private val weightsMapped: (artifactPath: String) -> Boolean = { true },
    /** The native runtime that measured (llama.cpp build, JNI revision): another one is another figure, never reused. */
    private val runtimeVersion: () -> String = { "" },
) {
    /** On a device: the app's SharedPreferences. */
    constructor(
        context: Context,
        weightsMapped: (artifactPath: String) -> Boolean = { true },
        runtimeVersion: () -> String = { "" },
    ) : this(SharedPreferencesStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)), weightsMapped, runtimeVersion)

    fun measurementFor(artifactPath: String, contextTokens: Int?): RamMeasurement? {
        val key = keyFor(artifactPath, contextTokens ?: return null) ?: return null
        return decode(prefs.getString(key))
    }

    @Synchronized
    fun record(artifactPath: String, contextTokens: Int, peakBytes: Long): RamMeasurement? {
        val key = keyFor(artifactPath, contextTokens) ?: return null
        val merged = decode(prefs.getString(key))?.merge(peakBytes) ?: RamMeasurement(peakBytes, 1)
        prefs.putString(key, "${merged.peakBytes},${merged.sampleCount}")
        return merged
    }

    /**
     * The anonymous growth this file showed the last time it was loaded
     * mapped and measured -- what decides how it loads next (see
     * [ai.localstudio.core.runtime.WeightsLoadPolicy]). Null when never.
     */
    /** Whether [artifactPath] loads mapped right now -- the same decision the runtime makes. */
    fun loadsMapped(artifactPath: String): Boolean = weightsMapped(artifactPath)

    fun mappedAnonymousBytes(artifactPath: String): Long? =
        profileKey(artifactPath)?.let { prefs.getString(it)?.toLongOrNull() }

    fun recordMappedAnonymous(artifactPath: String, anonymousBytes: Long) {
        val key = profileKey(artifactPath) ?: return
        prefs.putString(key, anonymousBytes.toString())
    }

    private fun profileKey(artifactPath: String): String? = fileIdentity(artifactPath)?.let { "mapped-anon|$it" }

    /**
     * Which file a figure belongs to: its path, size and modification time
     * (a different file put in its place is another file, even at the same
     * size) and the runtime that measured it.
     */
    private fun fileIdentity(artifactPath: String): String? {
        val file = File(artifactPath)
        if (!file.isFile) return null
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return "$canonical|${file.length()}|${file.lastModified()}|${runtimeVersion()}"
    }

    /** Drops what was measured for this file and context size; the next run measures from scratch. True when there was something. */
    @Synchronized
    fun forget(artifactPath: String, contextTokens: Int): Boolean {
        val key = keyFor(artifactPath, contextTokens) ?: return false
        if (prefs.getString(key) == null) return false
        prefs.remove(listOf(key))
        return true
    }

    /**
     * Drops every measurement of this file -- all context sizes, mapped or
     * read into memory: a device check is the measurement, and a figure an
     * older, possibly disturbed run left for another context size must not
     * outlive it (#492: the check measured Gemma 4 E4B at 2048 tokens, chat
     * then refused it on a September peak for 4096 tokens, ~9.8 GB). The
     * mapped-anonymous profile that picks the load mode stays. The number of
     * measurements dropped.
     */
    @Synchronized
    fun forgetAll(artifactPath: String): Int {
        val prefix = filePrefix(artifactPath) ?: return 0
        val keys = prefs.all().keys.filter { it.startsWith(prefix) }
        prefs.remove(keys)
        return keys.size
    }

    /** Every measurement of this file: context size, whether read into memory, and the figure -- for a person reading a check. */
    fun measurementsOf(artifactPath: String): List<Triple<Int, Boolean, RamMeasurement>> {
        val prefix = filePrefix(artifactPath) ?: return emptyList()
        return prefs.all().mapNotNull { (key, value) ->
            if (!key.startsWith(prefix)) return@mapNotNull null
            val rest = key.removePrefix(prefix)
            val read = rest.endsWith("|read")
            val ctx = rest.removeSuffix("|read").toIntOrNull() ?: return@mapNotNull null
            decode(value)?.let { Triple(ctx, read, it) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun filePrefix(artifactPath: String): String? = fileIdentity(artifactPath)?.let { "$KEY_VERSION|$it|" }

    private fun keyFor(artifactPath: String, contextTokens: Int): String? {
        val prefix = filePrefix(artifactPath) ?: return null
        // Mapped and read-into-memory weights cost differently (mapped pages and a repacked copy count twice): never mixed.
        val mode = if (weightsMapped(artifactPath)) "" else "|read"
        return "$prefix$contextTokens$mode"
    }

    private fun decode(raw: String?): RamMeasurement? {
        val parts = raw?.split(',') ?: return null
        val peak = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val count = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return runCatching { RamMeasurement(peak, count) }.getOrNull()
    }

    companion object {
        /** The SharedPreferences file the figures live in on a device. */
        const val PREFS_NAME = "measured_ram"
        // v2: from before the projector was loaded lazily, figures included it (~1 GB for Gemma's).
        // v3: keyed by file time and runtime too; v2 figures were max-merged across runs that also
        // counted mapped pages twice (#492: a September 4096-token peak refused Gemma 4 E4B at
        // ~9.8 GB right after its check measured 7.6) -- dropped, measured again on the next load.
        private const val KEY_VERSION = "v3"
    }
}
