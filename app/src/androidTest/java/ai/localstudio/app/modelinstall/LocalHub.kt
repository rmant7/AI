package ai.localstudio.app.modelinstall

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A miniature Hugging Face on 127.0.0.1, spoken to through the real
 * HttpURLConnection stack of the device:
 *
 *     GET /api/models/{repo}/revision/{rev}         → {"sha": commit}
 *     GET /api/models/{repo}/tree/{commit}[/{dir}]  → file listing (lfs.size, lfs.oid)
 *     GET /hf/{repo}/resolve/{commit}/{path}        → 302 to /cdn/{commit}/{repo}/{path}
 *     GET /cdn/{commit}/{repo}/{path}               → the bytes, with Range support
 *
 * Faults are queued per file path: [cutAfter] drops the connection after N
 * body bytes, [ignoreRange] answers 200 with the whole file, [corrupt]
 * serves different bytes than the listing's sha256 promises. Every request
 * is recorded in [requests].
 */
class LocalHub : Closeable {

    data class Request(val method: String, val path: String, val range: String?, val authorization: String?)

    private class HubFile(val bytes: ByteArray, val listedSize: Long, val lfs: Boolean)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val port: Int get() = server.localPort
    val base: String get() = "http://127.0.0.1:$port"

    private val branches = mutableMapOf<String, String>()
    private val files = mutableMapOf<String, MutableMap<String, HubFile>>()
    val cutAfter = mutableMapOf<String, ArrayDeque<Int>>()
    val ignoreRange = mutableSetOf<String>()
    val corrupt = mutableSetOf<String>()
    val requests = ConcurrentLinkedQueue<Request>()

    /** Rewrites a real Hugging Face URL to this hub, for [HttpRangeTransport]. */
    val rewrite: (String) -> String = { it.replace("https://huggingface.co/", "$base/hf/") }

    init {
        thread(isDaemon = true, name = "LocalHub") {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use { handle(it) } }
            }
        }
    }

    @Synchronized
    fun publish(repo: String, branch: String, commit: String, vararg content: Pair<String, ByteArray>, listedSize: Long? = null) {
        branches["$repo@$branch"] = commit
        val map = files.getOrPut("$repo@$commit") { mutableMapOf() }
        for ((path, bytes) in content) map[path] = HubFile(bytes, listedSize ?: bytes.size.toLong(), lfs = true)
    }

    fun fileRequests(): List<Request> = requests.filter { it.path.startsWith("/cdn/") }

    /** The body `GET /api/models?...` answers with (a JSON array); every query string it was asked with lands in [searchQueries]. */
    @Volatile
    var searchResponse: String = "[]"
    val searchQueries = ConcurrentLinkedQueue<String>()

    private fun handle(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
        val path = target.substringBefore('?')
        requests += Request(method, path, headers["range"], headers["authorization"])
        if (path == "/api/models") searchQueries += target.substringAfter('?', "")
        val out = socket.getOutputStream()
        synchronized(this) { route(path, headers, out) }
    }

    private fun route(path: String, headers: Map<String, String>, out: OutputStream) {
        val revision = Regex("^/api/models/([^/]+/[^/]+)/revision/([^/]+)$").matchEntire(path)
        val tree = Regex("^/api/models/([^/]+/[^/]+)/tree/([0-9a-f]{40})(?:/(.*))?$").matchEntire(path)
        val resolve = Regex("^/hf/([^/]+/[^/]+)/resolve/([0-9a-f]{40})/(.+)$").matchEntire(path)
        val cdn = Regex("^/cdn/([0-9a-f]{40})/([^/]+/[^/]+)/(.+)$").matchEntire(path)
        when {
            path == "/api/models" -> respond(out, 200, searchResponse)
            revision != null -> {
                val (repo, rev) = revision.destructured
                val commit = branches["$repo@$rev"] ?: return respond(out, 404, """{"error":"not found"}""")
                respond(out, 200, """{"id":"$repo","sha":"$commit"}""")
            }
            tree != null -> {
                val (repo, commit, dir) = tree.destructured
                val listing = files["$repo@$commit"] ?: return respond(out, 404, "[]")
                val entries = listing.filterKeys { it.substringBeforeLast('/', "") == dir }.map { (p, f) ->
                    val sha = sha256(f.bytes)
                    """{"type":"file","oid":"0000000000000000000000000000000000000000","size":134,"path":"$p",""" +
                        """"lfs":{"oid":"$sha","size":${f.listedSize},"pointerSize":134}}"""
                } + """{"type":"directory","oid":"1111111111111111111111111111111111111111","size":0,"path":"${if (dir.isEmpty()) "" else "$dir/"}subdir"}"""
                respond(out, 200, entries.joinToString(",", "[", "]"))
            }
            resolve != null -> {
                val (repo, commit, file) = resolve.destructured
                out.write(("HTTP/1.1 302 Found\r\nLocation: $base/cdn/$commit/$repo/$file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                out.flush()
            }
            cdn != null -> {
                val (commit, repo, file) = cdn.destructured
                val hubFile = files["$repo@$commit"]?.get(file) ?: return respond(out, 404, "missing")
                serveFile(out, file, hubFile, headers["range"])
            }
            else -> respond(out, 404, "no route")
        }
    }

    private fun serveFile(out: OutputStream, file: String, hubFile: HubFile, range: String?) {
        val bytes = if (file in corrupt) hubFile.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else hubFile.bytes
        val start = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLong() }
            ?.takeIf { file !in ignoreRange } ?: 0L
        if (start > 0 && start >= bytes.size) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${bytes.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val length = bytes.size - start
        val head = if (start > 0) {
            "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${bytes.size - 1}/${bytes.size}\r\n"
        } else {
            "HTTP/1.1 200 OK\r\n"
        }
        out.write((head + "Content-Length: $length\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n").toByteArray())
        val cut = cutAfter[file]?.removeFirstOrNull()
        val send = if (cut != null) minOf(cut.toLong(), length) else length
        out.write(bytes, start.toInt(), send.toInt())
        out.flush()
        // A cut just closes the socket mid-body: the client sees a short read / reset.
    }

    private fun respond(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray()
        out.write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    override fun close() {
        running.set(false)
        server.close()
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
