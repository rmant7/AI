package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRole
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.VariantId
import java.io.File

/**
 * Takes files a new install needs from what is already installed, so the
 * same bytes are never downloaded twice -- a real case: a model tested
 * text-only, then found again with its projector, would have fetched its
 * 1.7 GB weights a second time for the pair.
 *
 * Only by sha256: a file is reused when an installed one has exactly the
 * hash the new artifact pins (and the installer hashes it again before
 * accepting it, see [TransferEngine.download]). It goes into the new
 * install's staging directory as the finished file, where the installer
 * picks it up instead of downloading.
 *
 * Moved when [mayMove] says the installed one is superseded by this install
 * (the same model's earlier, smaller install) -- that install is then
 * removed, since it no longer has its file. Copied otherwise (a model the
 * user is using keeps its file): local disk instead of the network.
 * Android denies apps hard links in their own storage, so those are not an
 * option (see [LegacyMigrator]).
 */
object InstalledFileReuse {

    data class Reused(val role: ArtifactRole, val fileName: String, val fromVariant: VariantId, val moved: Boolean, val bytes: Long)

    fun seed(
        layout: InstallLayout,
        variant: ModelVariant,
        mayMove: (InstallManifest, InstalledArtifact) -> Boolean,
        copy: (from: File, to: File) -> Unit = { from, to -> from.copyTo(to, overwrite = true) },
    ): List<Reused> {
        val installed = InstalledVariants(layout)
        val others = installed.all().filter { it.variantId != variant.id }
        if (others.isEmpty()) return emptyList()
        val staging = layout.stagingDir(variant.id)
        val reused = mutableListOf<Reused>()
        val superseded = mutableSetOf<VariantId>()
        for (spec in variant.artifacts) {
            val sha = spec.sha256?.lowercase() ?: continue
            if (spec.unpack != null) continue
            val target = File(staging, spec.fileName)
            if (target.isFile) continue
            val (manifest, artifact) = others.asSequence()
                .flatMap { m -> m.artifacts.asSequence().map { m to it } }
                .firstOrNull { (m, a) -> a.unpackedDir == null && a.sha256.lowercase() == sha && installed.pathOf(m, a).isFile }
                ?: continue
            val source = installed.pathOf(manifest, artifact)
            staging.mkdirs()
            val moved = mayMove(manifest, artifact) && source.renameTo(target)
            if (moved) {
                superseded += manifest.variantId
            } else {
                val partial = File(target.path + ".copying")
                val copied = runCatching {
                    copy(source, partial)
                    check(partial.renameTo(target)) { "cannot move ${partial.name} into place" }
                }.isSuccess
                if (!copied) {
                    // Not enough space, say: the installer downloads it as it would have.
                    partial.delete()
                    continue
                }
            }
            reused += Reused(spec.role, spec.fileName, manifest.variantId, moved, target.length())
        }
        superseded.forEach { installed.uninstall(it) }
        return reused
    }
}
