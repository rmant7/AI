package ai.localstudio.app.modelinstall

import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.MadladLanguages
import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.model.ArtifactRole
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.install.LegacyInstallation
import android.content.Context
import java.io.File

/**
 * Finds what the legacy download code left on disk and says which catalogue
 * model each file claims to be — by the legacy stores' own naming (a file is
 * named after its seed id). That is a claim, not an identity: the migrator
 * proves or rejects it ([ai.localstudio.model.install.LegacyMigrator]). Nothing here reads file contents or touches a file.
 *
 *     models/<id>.gguf (+ <id>.mmproj.gguf)   LocalModels, TranslationModels, custom-* repos
 *     whisper/<id>.bin                        WhisperModels
 *     experimental_embeddings/<id>.gguf       ExperimentalEmbeddingModels
 *     vosk-models/<id>/                       VoskModels (unpacked; the zip is deleted)
 *
 * In-progress downloads (`.part`) are ignored. A file whose name maps to no
 * model — an unknown id, a custom Whisper URL (its file name is a hash of the
 * URL, the URL itself is gone) — is reported in [Result.unrecognized].
 */
class LegacyInstallationScanner(
    private val baseDir: File,
    models: List<ModelDefinition>,
) {
    data class Result(val installations: List<LegacyInstallation>, val unrecognized: List<File>)

    private val byId = models.associateBy { it.id.id }

    fun scan(): Result {
        val found = mutableListOf<LegacyInstallation>()
        val unrecognized = mutableListOf<File>()

        fun claim(id: String, files: Map<ArtifactRole, File>, origin: String, fallback: () -> ModelDefinition? = { null }) {
            val model = byId[id] ?: fallback()
            val variant = model?.variants?.singleOrNull()
            if (model == null || variant == null) unrecognized += files.values else found += LegacyInstallation(model, variant, files, origin)
        }

        dir("models").filesOrEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf") && !it.name.endsWith(".mmproj.gguf") }
            .forEach { weights ->
                val id = weights.name.removeSuffix(".gguf")
                val projector = File(weights.parentFile, "$id.mmproj.gguf").takeIf { it.isFile }
                val files = buildMap {
                    put(ArtifactRoles.WEIGHTS, weights)
                    projector?.let { put(ArtifactRoles.PROJECTOR, it) }
                }
                claim(id, files, "models/${weights.name}") {
                    LocalModels.repoIdFromCustomFileName(weights.name)?.let { LegacyCatalogMapper.customModel(LocalModels.custom(it)) }
                }
            }

        dir("whisper").filesOrEmpty()
            .filter { it.isFile && it.name.endsWith(".bin") }
            .forEach { claim(it.name.removeSuffix(".bin"), mapOf(ArtifactRoles.WEIGHTS to it), "whisper/${it.name}") }

        dir("experimental_embeddings").filesOrEmpty()
            .filter { it.isFile && it.name.endsWith(".gguf") }
            .forEach { claim(it.name.removeSuffix(".gguf"), mapOf(ArtifactRoles.WEIGHTS to it), "experimental_embeddings/${it.name}") }

        dir("vosk-models").filesOrEmpty()
            .filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            .forEach { claim(it.name, mapOf(ArtifactRoles.ARCHIVE to it), "vosk-models/${it.name}/") }

        return Result(found.sortedBy { it.origin }, unrecognized.sortedBy { it.path })
    }

    private fun dir(name: String) = File(baseDir, name)

    private fun File.filesOrEmpty(): List<File> = listFiles()?.sortedBy { it.name }.orEmpty()

    companion object {
        /** The scanner over the app's own files, against the catalogue the legacy seeds describe. */
        fun forApp(context: Context): LegacyInstallationScanner = LegacyInstallationScanner(
            context.filesDir,
            LegacyCatalogMapper.bundledDocument(MadladLanguages.load(context).map { it.code }).models,
        )
    }
}
