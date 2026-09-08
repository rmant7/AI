package ai.localstudio.app.models

import android.content.Context
import java.io.File

/**
 * Where downloaded models live.
 *
 * Files are named after the seed's stable id, never after the repository's file
 * name: a repo that renames its quant files would otherwise leave the app
 * unable to find a model it has already downloaded, and downloading it again.
 *
 * The directory is app-private storage rather than the cache directory —
 * several gigabytes fetched over mobile data must not be something the system
 * can reclaim on a whim.
 */
class ModelStore(private val context: Context) {

    fun directory(): File = File(context.filesDir, "models").apply { mkdirs() }

    fun fileFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.gguf")

    fun partFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.gguf.part")

    /** A truncated download is not an installed model, hence the size floor. */
    fun isInstalled(seed: LocalModelSeed): Boolean =
        fileFor(seed).let { it.isFile && it.length() > MIN_PLAUSIBLE_SIZE }

    /**
     * Total disk footprint of this model — the main GGUF plus its projector
     * when one is installed. Reporting only the main file's size here left
     * the "Установлена · N ГБ" status understating actual usage by however
     * big the mmproj file was, which is not a rounding error: this model's
     * own projector is roughly a third of the main file's size on top.
     */
    fun installedSize(seed: LocalModelSeed): Long =
        (fileFor(seed).takeIf { it.isFile }?.length() ?: 0) +
            (mmprojFileFor(seed).takeIf { it.isFile }?.length() ?: 0)

    fun partialSize(seed: LocalModelSeed): Long = partFor(seed).takeIf { it.isFile }?.length() ?: 0

    fun delete(seed: LocalModelSeed) {
        fileFor(seed).delete()
        partFor(seed).delete()
        mmprojFileFor(seed).delete()
        mmprojPartFor(seed).delete()
    }

    /**
     * A vision-capable model's projector, downloaded and named separately
     * from its main GGUF — llama.cpp keeps the two apart, and this app
     * mirrors that rather than trying to merge them into one file.
     */
    fun mmprojFileFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.mmproj.gguf")

    fun mmprojPartFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}.mmproj.gguf.part")

    /**
     * True once the projector is actually on disk, not just declared by the
     * seed — a model whose main GGUF finished but whose (much smaller,
     * best-effort) projector download failed is still usable, just
     * text-only, and this is what [LlamaCppRuntime] checks to know which.
     */
    fun hasMmproj(seed: LocalModelSeed): Boolean =
        seed.mmprojFileName != null && mmprojFileFor(seed).let { it.isFile && it.length() > 0 }

    fun freeSpaceBytes(): Long = directory().freeSpace

    private companion object {
        const val MIN_PLAUSIBLE_SIZE = 50L * 1024 * 1024
    }
}
