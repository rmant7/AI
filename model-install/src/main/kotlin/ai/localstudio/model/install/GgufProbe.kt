package ai.localstudio.model.install

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads a remote GGUF file's header -- only the first bytes of the file,
 * over the same [HttpTransport] an install uses -- and judges it with
 * [GgufCompatibility] before a single weight is downloaded. The first step
 * of discovery: a candidate the bundled llama.cpp cannot load is dropped
 * for a few kilobytes instead of a few gigabytes.
 *
 * Reading stops as soon as [stopWhen] is satisfied (by default: the
 * architecture and its context length, both at the very start of every
 * GGUF llama.cpp writes); the connection is then closed. [maxBytes] caps
 * what one probe may read, so a server that sends something else, or a
 * header with a huge vocabulary when [stopWhen] needs a key past it,
 * costs at most that much.
 */
class GgufProbe(
    private val transport: HttpTransport,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    sealed interface Result {
        data class Probed(val metadata: GgufMetadata, val compatibility: GgufCompatibility, val bytesRead: Long) : Result

        /** Not a verdict about the model: the header could not be read (network, not a GGUF, over [maxBytes]). */
        data class Unreadable(val reason: String) : Result
    }

    fun probe(url: String, stopWhen: (Map<String, Any>) -> Boolean = ::hasArchitectureAndContext): Result {
        val body = try {
            transport.open(url, 0)
        } catch (e: IOException) {
            return Result.Unreadable("could not open $url: ${e.message}")
        }
        return body.use {
            val counting = CountingStream(it.stream, maxBytes)
            try {
                val metadata = GgufMetadataReader.read(counting, stopWhen)
                Result.Probed(metadata, GgufCompatibility.of(metadata), counting.count)
            } catch (e: BudgetExceeded) {
                Result.Unreadable("header not finished within $maxBytes bytes")
            } catch (e: GgufFormatException) {
                Result.Unreadable(e.message ?: "not a GGUF header")
            } catch (e: IOException) {
                Result.Unreadable("reading $url failed: ${e.message}")
            }
        }
    }

    /** Probes the first download candidate of a resolved weights artifact -- the commit-pinned URL an install would fetch. */
    fun probe(artifact: ResolvedArtifact, stopWhen: (Map<String, Any>) -> Boolean = ::hasArchitectureAndContext): Result =
        probe(artifact.candidates.first().url, stopWhen)

    private class BudgetExceeded : IOException()

    private class CountingStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        var count = 0L
            private set

        private fun add(n: Long) {
            if (n > 0) count += n
            if (count > max) throw BudgetExceeded()
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { add(it) }
    }

    companion object {
        /** Enough for any header's general.* and <arch>.* keys; a full vocabulary can be several MB more. */
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024

        fun hasArchitectureAndContext(values: Map<String, Any>): Boolean {
            val arch = values[GgufMetadata.KEY_ARCHITECTURE] as? String ?: return false
            return "$arch.context_length" in values
        }
    }
}
