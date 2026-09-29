package ai.localstudio.app.voicebenchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The benchmark results, kept on disk next to the WAVs they point at, so they
 * survive the screen being recreated (a language change, rotation) and the
 * process being killed — what was generated stays there to listen to and
 * compare until someone clears it.
 */
class VoiceBenchmarkStore(private val file: File) {

    fun save(results: Collection<VoiceBenchmarkResult>) {
        runCatching {
            val array = JSONArray()
            for (r in results) {
                array.put(
                    JSONObject()
                        .put("engineId", r.engineId)
                        .put("success", r.success)
                        .put("audioFile", r.audioFile?.absolutePath)
                        .put("generationMs", r.generationMs)
                        .put("audioDurationMs", r.audioDurationMs)
                        .put("rtf", r.rtf)
                        .put("error", r.error)
                        .put("status", r.status.name)
                        .put("loadMs", r.loadMs)
                        .put("voicePrepMs", r.voicePrepMs)
                        .put("firstAudioMs", r.firstAudioMs)
                        .put("memoryBeforeMb", r.memoryBeforeMb)
                        .put("memoryAfterMb", r.memoryAfterMb)
                        .put("availRamMb", r.availRamMb)
                        .put("details", r.details)
                        .put("language", r.language),
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }

    /** Results whose audio file is still there (or that never had one). */
    fun load(): List<VoiceBenchmarkResult> = runCatching {
        val array = JSONArray(file.takeIf { it.exists() }?.readText() ?: return emptyList())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val audio = o.optString("audioFile").takeIf { it.isNotEmpty() && it != "null" }?.let(::File)
            if (audio != null && !audio.exists()) return@mapNotNull null
            fun long(key: String) = if (o.isNull(key)) null else o.optLong(key)
            VoiceBenchmarkResult(
                engineId = o.getString("engineId"),
                success = o.getBoolean("success"),
                audioFile = audio,
                generationMs = long("generationMs"),
                audioDurationMs = long("audioDurationMs"),
                rtf = if (o.isNull("rtf")) null else o.optDouble("rtf"),
                error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                status = runCatching { VoiceBenchmarkStatus.valueOf(o.getString("status")) }.getOrDefault(VoiceBenchmarkStatus.ERROR),
                loadMs = long("loadMs"),
                voicePrepMs = long("voicePrepMs"),
                firstAudioMs = long("firstAudioMs"),
                memoryBeforeMb = long("memoryBeforeMb"),
                memoryAfterMb = long("memoryAfterMb"),
                availRamMb = long("availRamMb"),
                details = o.optString("details").takeIf { it.isNotEmpty() && it != "null" },
                language = o.optString("language").takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())
}
