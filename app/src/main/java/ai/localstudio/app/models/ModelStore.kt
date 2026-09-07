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

    fun fileFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}${seed.extension}")

    fun partFor(seed: LocalModelSeed): File = File(directory(), "${seed.id}${seed.extension}.part")

    /** A truncated download is not an installed model, hence the size floor. */
    fun isInstalled(seed: LocalModelSeed): Boolean =
        fileFor(seed).let { it.isFile && it.length() > MIN_PLAUSIBLE_SIZE }

    fun installedSize(seed: LocalModelSeed): Long = fileFor(seed).takeIf { it.isFile }?.length() ?: 0

    fun partialSize(seed: LocalModelSeed): Long = partFor(seed).takeIf { it.isFile }?.length() ?: 0

    fun delete(seed: LocalModelSeed) {
        fileFor(seed).delete()
        partFor(seed).delete()
    }

    fun freeSpaceBytes(): Long = directory().freeSpace

    private companion object {
        const val MIN_PLAUSIBLE_SIZE = 50L * 1024 * 1024
    }
}
