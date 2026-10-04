package ai.localstudio.model.install

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * The Hugging Face metadata resolution needs. Implemented over HTTP by the
 * app, faked in tests. Failures are reported as [SourceException] so the
 * resolver can tell "this repository is gated" from "no network at all".
 */
interface HuggingFaceMetadata {
    /** The commit [revision] (a branch, tag or commit) points at right now. */
    fun resolveCommit(repo: String, revision: String): String

    /**
     * The files (not folders) directly inside [directory] ("" = repository
     * root) of [repo] at [commit] — non-recursive, like the legacy listing.
     */
    fun listFiles(repo: String, commit: String, directory: String = ""): List<RepoFile>
}

/**
 * One file of a repository listing. [sizeBytes] must be the real size — for
 * an LFS file that is `lfs.size`, not the pointer's `size`. [lfsSha256] is
 * the hash Hugging Face reports for an LFS file (`lfs.oid`), null otherwise.
 */
data class RepoFile(val path: String, val sizeBytes: Long, val lfsSha256: String? = null) {
    val name: String get() = path.substringAfterLast('/')
}

/** A source failed in a way worth telling apart. */
class SourceException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        /** No route to the host at all — trying another repository on the same host is pointless. */
        NETWORK,

        /** 404 / no such repository or file. */
        NOT_FOUND,

        /** 401 / 403 — gated repository, missing or insufficient token. */
        ACCESS_DENIED,

        OTHER,
    }
}

/** Opens HTTP(S) downloads. Implemented by the app (HttpURLConnection, OkHttp, ...), faked in tests. */
interface HttpTransport {
    /**
     * GET [url] starting at byte [offset] (a Range request when > 0). Throws
     * [SourceException] / [IOException] for anything but a 2xx answer.
     */
    fun open(url: String, offset: Long): HttpBody
}

/**
 * An open response body. [offset] is where the body actually starts: equal
 * to the requested offset when the server honoured the Range request, 0 when
 * it sent the whole file instead. [totalBytes] is the full file size when
 * the server said so (Content-Length / Content-Range), null otherwise.
 */
class HttpBody(
    val offset: Long,
    val totalBytes: Long?,
    val stream: InputStream,
) : Closeable {
    override fun close() = stream.close()
}

/** What the device offers right now — sampled by the app, passed in. */
data class DeviceProfile(
    val androidApi: Int,
    val cpuFeatures: Set<String> = emptySet(),
    val hasGpu: Boolean = false,
    val hasNpu: Boolean = false,
    /** Runtimes this build actually ships (llama_cpp, whisper_cpp, vosk, ...). */
    val runtimes: Set<ai.localstudio.model.RuntimeId>,
)

/** Cooperative cancellation for long transfers; checked between chunks. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    companion object {
        val NONE = CancellationSignal { false }
    }
}

class TransferCancelledException : IOException("cancelled")
