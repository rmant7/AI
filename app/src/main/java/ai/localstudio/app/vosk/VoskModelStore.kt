package ai.localstudio.app.vosk

import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.VariantId
import ai.localstudio.model.install.InstallHealth
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
 *
 * Phase 3c.3: once [installation] is set (by AppContainer, at start), a
 * model installed through the new chain -- the archive downloaded,
 * unpacked by the installer with the same top-level folder stripped, a
 * manifest written -- is found in the model store first; the legacy
 * `vosk-models/<id>/` stays the fallback. An object with a settable chain
 * rather than a class because every caller (recognizer, file transcriber,
 * the Transcribe and Models screens) reaches it with only a Context.
 */
object VoskModelStore {

    @Volatile
    var installation: ModelInstallation? = null

    fun directory(context: Context): File = File(context.filesDir, "vosk-models").apply { mkdirs() }

    /** The directory `Model(path)` loads: the store's unpacked copy when intact there, else the legacy one. */
    fun modelDir(context: Context, seed: VoskModelSeed): File = storeDir(seed) ?: legacyModelDir(context, seed)

    fun legacyModelDir(context: Context, seed: VoskModelSeed): File = File(directory(context), seed.id)

    fun isInNewStore(seed: VoskModelSeed): Boolean = storeDir(seed) != null

    fun variantId(seed: VoskModelSeed): VariantId = VariantId(seed.id + LegacyCatalogMapper.LEGACY_VARIANT_SUFFIX)

    /**
     * An unpacked archive's health is its byte count (no per-file hashes
     * exist for these archives); a directory Vosk reads but never writes
     * stays intact.
     */
    private fun storeDir(seed: VoskModelSeed): File? {
        val installed = installation?.installed ?: return null
        val manifest = installed.manifest(variantId(seed))?.takeIf { installed.health(it) == InstallHealth.Intact } ?: return null
        val archive = manifest.artifacts.firstOrNull { it.role == ArtifactRoles.ARCHIVE } ?: return null
        return installed.pathOf(manifest, archive).takeIf { it.isDirectory }
    }
    fun zipFile(context: Context, seed: VoskModelSeed): File = File(directory(context), "${seed.id}.zip")
    fun zipPartFile(context: Context, seed: VoskModelSeed): File = File(directory(context), "${seed.id}.zip.part")

    fun isInstalled(context: Context, seed: VoskModelSeed): Boolean {
        if (isInNewStore(seed)) return true
        val dir = legacyModelDir(context, seed)
        return dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    /** [preferredId] wins if that size is actually installed; otherwise the largest installed one — same resolution as [ai.localstudio.app.whisper.WhisperStore.installedSeed]. */
    fun installedSeed(context: Context, preferredId: String?): VoskModelSeed? {
        val preferred = preferredId?.let { VoskModels.byId(it) }?.takeIf { isInstalled(context, it) }
        return preferred ?: VoskModels.SEEDS.filter { isInstalled(context, it) }.maxByOrNull { it.approxSizeBytes }
    }

    fun delete(context: Context, seed: VoskModelSeed) {
        installation?.let { it.installed.uninstall(variantId(seed)); it.layout.stagingDir(variantId(seed)).deleteRecursively() }
        legacyModelDir(context, seed).deleteRecursively()
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
