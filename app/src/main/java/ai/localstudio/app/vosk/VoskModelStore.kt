package ai.localstudio.app.vosk

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Where downloaded Vosk models live — one directory per [VoskModelSeed],
 * each holding an unpacked model (`am/`, `conf/`, `graph/`, …) the way
 * `org.vosk.Model(path)` expects, plus [extract] to get there from the zip
 * [VoskDownloads] downloads. Mirrors [ai.localstudio.app.whisper.WhisperStore]'s
 * role for Whisper's `.bin` files.
 */
object VoskModelStore {

    fun directory(context: Context): File = File(context.filesDir, "vosk-models").apply { mkdirs() }

    fun modelDir(context: Context, seed: VoskModelSeed): File = File(directory(context), seed.id)
    fun zipFile(context: Context, seed: VoskModelSeed): File = File(directory(context), "${seed.id}.zip")
    fun zipPartFile(context: Context, seed: VoskModelSeed): File = File(directory(context), "${seed.id}.zip.part")

    fun isInstalled(context: Context, seed: VoskModelSeed): Boolean {
        val dir = modelDir(context, seed)
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    /** [preferredId] wins if that size is actually installed; otherwise the largest installed one — same resolution as [ai.localstudio.app.whisper.WhisperStore.installedSeed]. */
    fun installedSeed(context: Context, preferredId: String?): VoskModelSeed? {
        val preferred = preferredId?.let { VoskModels.byId(it) }?.takeIf { isInstalled(context, it) }
        return preferred ?: VoskModels.SEEDS.filter { isInstalled(context, it) }.maxByOrNull { it.approxSizeBytes }
    }

    fun delete(context: Context, seed: VoskModelSeed) {
        modelDir(context, seed).deleteRecursively()
        zipFile(context, seed).delete()
        zipPartFile(context, seed).delete()
    }

    /**
     * Unzips [zip] into [modelDir], stripping the single top-level directory
     * every official Vosk archive wraps its contents in (e.g. a
     * `vosk-model-small-ru-0.22/am/final.mdl` entry becomes `am/final.mdl`
     * under [modelDir]) — `Model(path)` expects `am/`, `conf/`, `graph/`, …
     * directly inside [modelDir], not one level down.
     */
    fun extract(zip: File, modelDir: File) {
        if (modelDir.exists()) modelDir.deleteRecursively()
        modelDir.mkdirs()
        val root = modelDir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val strippedName = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (strippedName.isNotBlank() && !entry.isDirectory) {
                    val outFile = File(modelDir, strippedName)
                    // Guards against a zip entry whose name climbs out of
                    // modelDir via "../" — official Vosk archives don't do
                    // this, but nothing about a zip's own format stops one
                    // from claiming to.
                    if (!outFile.canonicalFile.startsWith(root)) {
                        throw IOException("Zip entry escapes model directory: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zis.copyTo(output) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
