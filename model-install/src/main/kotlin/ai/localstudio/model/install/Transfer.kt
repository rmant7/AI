package ai.localstudio.model.install

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** A downloaded file whose bytes were checked as far as [integrity] allows. */
data class VerifiedFile(
    val file: File,
    val sizeBytes: Long,
    /** Always computed, whatever [integrity] is — the manifest records what was installed. */
    val sha256: String,
    val integrity: IntegrityBasis,
    val candidate: DownloadCandidate,
)

data class TransferProgress(val bytesDone: Long, val bytesTotal: Long?, val url: String)

sealed interface TransferOutcome {
    data class Done(val file: VerifiedFile, val failures: List<SourceFailure>) : TransferOutcome

    data class Failed(val failures: List<SourceFailure>) : TransferOutcome
}

object Sha256 {
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Downloads one [ResolvedArtifact] to a target file: candidates in order,
 * each retried with resume, the result verified before it is accepted.
 *
 * - Bytes accumulate in `<target>.part`; a sidecar `<target>.part.json`
 *   records which candidate (URL + expected hash/size) they belong to, so a
 *   resume — also after the process died — continues only the same bytes.
 *   Moving on to another candidate discards the part: an alternative source
 *   may hold different bytes, and splicing two files together is exactly
 *   the corruption a resume must never produce.
 * - Network and I/O errors are retried on the same candidate, resuming;
 *   NOT_FOUND / ACCESS_DENIED move straight on — retrying won't change them.
 * - A finished file larger than expected, or with the wrong sha256, is
 *   deleted and the candidate is given up (the server sends those bytes
 *   deterministically, a retry would fetch them again).
 * - Cancellation, and a candidate given up on network errors, keep the part
 *   for a later run, which continues it before trying anything else.
 */
class TransferEngine(
    private val transport: HttpTransport,
    private val attemptsPerCandidate: Int = 3,
    private val retryDelayMs: Long = 2_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    fun download(
        artifact: ResolvedArtifact,
        target: File,
        cancel: CancellationSignal = CancellationSignal.NONE,
        progress: (TransferProgress) -> Unit = {},
    ): TransferOutcome {
        val part = File(target.path + ".part")
        val meta = File(target.path + ".part.json")
        target.parentFile?.mkdirs()
        val failures = mutableListOf<SourceFailure>()
        val fromCatalog = artifact.spec.sha256 != null

        // Already downloaded by an earlier, interrupted install — reuse it only
        // when a hash proves it; a size match alone is no proof.
        if (target.isFile) {
            val sha = Sha256.of(target)
            artifact.candidates.firstOrNull { it.expectedSha256 == sha }?.let { candidate ->
                return TransferOutcome.Done(VerifiedFile(target, target.length(), sha, candidate.integrity(fromCatalog), candidate), failures)
            }
            target.delete()
        }

        // A part left by an interrupted run is continued first, whichever candidate it belongs to.
        val leftover = readIdentity(meta)?.takeIf { part.isFile }
        val ordered = artifact.candidates.sortedByDescending { it.identity() == leftover }

        for (candidate in ordered) {
            val identity = candidate.identity()
            if (readIdentity(meta) != identity) {
                part.delete()
                writeIdentity(meta, identity)
            }
            val result = tryCandidate(candidate, part, cancel, progress)
            if (result != null) {
                failures += result
                // A network failure keeps its bytes for a later resume (moving to another
                // candidate discards them via the identity check); known-bad bytes go now.
                if (result.kind != SourceException.Kind.NETWORK) {
                    part.delete()
                    meta.delete()
                }
                continue
            }
            val size = part.length()
            val sha = Sha256.of(part)
            if (candidate.expectedSha256 != null && sha != candidate.expectedSha256) {
                failures += SourceFailure(candidate.url, SourceException.Kind.OTHER, "sha256 $sha, expected ${candidate.expectedSha256}")
                part.delete()
                meta.delete()
                continue
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("cannot move ${part.name} into place")
            meta.delete()
            return TransferOutcome.Done(VerifiedFile(target, size, sha, candidate.integrity(fromCatalog), candidate), failures)
        }
        return TransferOutcome.Failed(failures)
    }

    /** Null when [part] now holds the complete file (size-checked); otherwise why this candidate was given up. */
    private fun tryCandidate(
        candidate: DownloadCandidate,
        part: File,
        cancel: CancellationSignal,
        progress: (TransferProgress) -> Unit,
    ): SourceFailure? {
        var last: SourceFailure? = null
        repeat(attemptsPerCandidate) { attempt ->
            if (attempt > 0) sleep(retryDelayMs)
            if (cancel.isCancelled()) throw TransferCancelledException()
            val expected = candidate.expectedSizeBytes
            if (expected != null && part.length() > expected) part.delete()
            if (expected != null && part.length() == expected) return null

            try {
                val total = fetch(candidate, part, cancel, progress)
                val length = part.length()
                when {
                    expected != null && length > expected -> {
                        return SourceFailure(candidate.url, SourceException.Kind.OTHER, "got $length bytes, expected $expected")
                    }
                    expected != null && length < expected -> {
                        last = SourceFailure(candidate.url, SourceException.Kind.NETWORK, "stream ended at $length of $expected bytes")
                    }
                    total != null && length != total -> {
                        last = SourceFailure(candidate.url, SourceException.Kind.NETWORK, "stream ended at $length of $total bytes")
                    }
                    else -> return null
                }
            } catch (e: TransferCancelledException) {
                throw e
            } catch (e: SourceException) {
                val failure = SourceFailure(candidate.url, e.kind, e.message ?: e.kind.name)
                if (e.kind == SourceException.Kind.NOT_FOUND || e.kind == SourceException.Kind.ACCESS_DENIED) return failure
                last = failure
            } catch (e: IOException) {
                last = SourceFailure(candidate.url, SourceException.Kind.NETWORK, e.message ?: e.toString())
            }
        }
        return last
    }

    /** Appends to [part] from where it stops; returns the full size the server announced, if any. */
    private fun fetch(candidate: DownloadCandidate, part: File, cancel: CancellationSignal, progress: (TransferProgress) -> Unit): Long? {
        val have = part.length()
        transport.open(candidate.url, have).use { body ->
            val append = when (body.offset) {
                have -> true
                0L -> false // server ignored the Range request: start over
                else -> throw IOException("server resumed at ${body.offset}, asked for $have")
            }
            val total = candidate.expectedSizeBytes ?: body.totalBytes
            var done = if (append) have else 0L
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    if (cancel.isCancelled()) throw TransferCancelledException()
                    val read = body.stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    progress(TransferProgress(done, total, candidate.url))
                }
            }
            return body.totalBytes
        }
    }

    private fun DownloadCandidate.identity() = PartIdentity(url, expectedSha256, expectedSizeBytes)

    @Serializable
    private data class PartIdentity(val url: String, val sha256: String?, val sizeBytes: Long?)

    private val json = Json { ignoreUnknownKeys = true }

    private fun readIdentity(meta: File): PartIdentity? =
        runCatching { json.decodeFromString(PartIdentity.serializer(), meta.readText()) }.getOrNull()

    private fun writeIdentity(meta: File, identity: PartIdentity) {
        meta.writeText(json.encodeToString(PartIdentity.serializer(), identity))
    }
}
