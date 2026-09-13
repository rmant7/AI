package ai.localstudio.app.llama

import android.content.Context
import java.io.File

/**
 * Where a candidate embedding model's GGUF lands once downloaded through
 * [ExperimentalEmbeddingsActivity] — private app storage, same reasoning as
 * [ai.localstudio.app.models.ModelStore]: several hundred megabytes fetched
 * over mobile data must not be something the system reclaims on a whim, and
 * a phone-only workflow (no adb) has no other way to get the file onto the
 * device at all.
 *
 * Deliberately separate from [ai.localstudio.app.models.ModelStore]: these
 * are not [ai.localstudio.app.models.LocalModelSeed] catalog entries, and
 * must never be mistaken for one — see [ExperimentalEmbeddingModels]'s own
 * doc comment on why none of this is wired into the chat-model catalog.
 */
class ExperimentalEmbeddingStore(private val context: Context) {

    fun directory(): File = File(context.filesDir, "experimental_embeddings").apply { mkdirs() }

    fun fileFor(spec: EmbeddingModelSpec): File = File(directory(), "${spec.id}.gguf")

    fun partFor(spec: EmbeddingModelSpec): File = File(directory(), "${spec.id}.gguf.part")

    fun isInstalled(spec: EmbeddingModelSpec): Boolean =
        fileFor(spec).let { it.isFile && it.length() > MIN_PLAUSIBLE_SIZE }

    fun installedSize(spec: EmbeddingModelSpec): Long = fileFor(spec).takeIf { it.isFile }?.length() ?: 0

    fun partialSize(spec: EmbeddingModelSpec): Long = partFor(spec).takeIf { it.isFile }?.length() ?: 0

    fun delete(spec: EmbeddingModelSpec) {
        fileFor(spec).delete()
        partFor(spec).delete()
    }

    fun freeSpaceBytes(): Long = directory().freeSpace

    private companion object {
        // A truncated download is not a usable model — same floor ModelStore
        // uses, small enough not to reject even the smallest real candidate.
        const val MIN_PLAUSIBLE_SIZE = 1L * 1024 * 1024
    }
}
