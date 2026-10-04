package ai.localstudio.model.install

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun bytesOf(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed * 7) % 251).toByte() }

/** In-memory Hugging Face: repo → branch → commit, and the files of each commit. */
class FakeHuggingFace : HuggingFaceMetadata {
    val branches = mutableMapOf<String, MutableMap<String, String>>()
    val files = mutableMapOf<Pair<String, String>, MutableList<RepoFile>>()
    val failures = mutableMapOf<String, SourceException>()
    val calls = mutableListOf<String>()

    fun repo(repo: String, commit: String, vararg repoFiles: RepoFile, branch: String = "main") {
        branches.getOrPut(repo) { mutableMapOf() }[branch] = commit
        files.getOrPut(repo to commit) { mutableListOf() } += repoFiles
    }

    override fun resolveCommit(repo: String, revision: String): String {
        calls += "commit $repo@$revision"
        failures[repo]?.let { throw it }
        if (Regex("^[0-9a-f]{40}$").matches(revision)) return revision
        return branches[repo]?.get(revision) ?: throw SourceException(SourceException.Kind.NOT_FOUND, "no $repo@$revision")
    }

    override fun listFiles(repo: String, commit: String, directory: String): List<RepoFile> {
        calls += "list $repo@$commit/$directory"
        failures[repo]?.let { throw it }
        return files[repo to commit].orEmpty().filter { it.path.substringBeforeLast('/', "") == directory }
    }
}

/**
 * In-memory HTTP: url → bytes. [cutAfter] makes the next opens of a URL die
 * after that many bytes (once per queued entry); [ignoreRange] makes the
 * server always send the whole body; [errors] fails opens outright.
 */
class FakeTransport : HttpTransport {
    val bodies = mutableMapOf<String, ByteArray>()
    val cutAfter = mutableMapOf<String, ArrayDeque<Int>>()
    val ignoreRange = mutableSetOf<String>()
    val errors = mutableMapOf<String, ArrayDeque<IOException>>()
    val opens = mutableListOf<Pair<String, Long>>()

    override fun open(url: String, offset: Long): HttpBody {
        opens += url to offset
        errors[url]?.removeFirstOrNull()?.let { throw it }
        val body = bodies[url] ?: throw SourceException(SourceException.Kind.NOT_FOUND, "404 $url")
        val start = if (url in ignoreRange) 0L else offset
        val cut = cutAfter[url]?.removeFirstOrNull()
        val stream: InputStream = ByteArrayInputStream(body, start.toInt(), body.size - start.toInt()).let { inner ->
            if (cut == null) inner else CuttingStream(inner, cut)
        }
        return HttpBody(start, body.size.toLong(), stream)
    }

    private class CuttingStream(private val inner: InputStream, private var left: Int) : InputStream() {
        override fun read(): Int {
            if (left <= 0) throw IOException("connection reset")
            left--
            return inner.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) throw IOException("connection reset")
            val n = inner.read(b, off, minOf(len, left))
            if (n > 0) left -= n
            return n
        }
    }
}

const val COMMIT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const val COMMIT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

val noSleep: (Long) -> Unit = {}
