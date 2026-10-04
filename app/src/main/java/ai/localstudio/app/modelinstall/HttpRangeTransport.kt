package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.HttpBody
import ai.localstudio.model.install.HttpTransport
import ai.localstudio.model.install.SourceException
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * [HttpTransport] over HttpURLConnection with Range resume:
 * - 206 → the body starts where `Content-Range` says, total from its `/N`;
 * - 200 → the server sent the whole file (ignored or didn't get the Range): offset 0;
 * - 416 with `Content-Range: bytes * /N` where N is exactly the requested
 *   offset → the part is already complete (a run killed between the last
 *   byte and the rename), an empty body at that offset;
 * - other statuses → [SourceException] (see [HttpConnections]).
 *
 * [token] goes only to Hugging Face, on the first hop — a `resolve` URL of
 * a gated repository needs it, the CDN it redirects to must not see it.
 * [rewrite] maps a URL before it is opened: identity in the app, a local
 * server in the on-device test harness.
 */
class HttpRangeTransport(
    private val token: () -> String? = { null },
    private val rewrite: (String) -> String = { it },
) : HttpTransport {

    override fun open(url: String, offset: Long): HttpBody {
        val original = URI.create(url)
        val connection = HttpConnections.open(
            rewrite(url),
            headers = buildMap {
                put("Accept", "*/*")
                // Byte-exact resume needs the identity encoding: a gzip'd body would shift every offset.
                put("Accept-Encoding", "identity")
                if (offset > 0) put("Range", "bytes=$offset-")
            },
            bearer = token(),
            sendBearer = { original.host == HUGGING_FACE_HOST },
        )
        val status = connection.responseCode
        return when (status) {
            HttpURLConnection.HTTP_PARTIAL -> {
                val range = parseContentRange(connection.getHeaderField("Content-Range"))
                    ?: run {
                        connection.disconnect()
                        throw SourceException(SourceException.Kind.OTHER, "206 without a usable Content-Range")
                    }
                HttpBody(range.first, range.second, stream(connection))
            }
            416 -> {
                val total = connection.getHeaderField("Content-Range")?.substringAfter("*/", "")?.toLongOrNull()
                connection.disconnect()
                if (total != null && total == offset) {
                    HttpBody(offset, total, ByteArrayInputStream(ByteArray(0)))
                } else {
                    throw SourceException(SourceException.Kind.OTHER, "HTTP 416 at offset $offset (file is $total bytes)")
                }
            }
            else -> HttpBody(0, connection.contentLengthLong.takeIf { it >= 0 }, stream(connection))
        }
    }

    private fun stream(connection: HttpURLConnection): InputStream {
        val input = try {
            connection.inputStream
        } catch (e: IOException) {
            connection.disconnect()
            throw HttpConnections.classify(e)
        }
        return object : FilterInputStream(input) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                super.read(b, off, len)
            } catch (e: IOException) {
                throw HttpConnections.classify(e)
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    internal companion object {
        const val HUGGING_FACE_HOST = "huggingface.co"

        /** "bytes 100-999/1000" → (100, 1000); total null for "/ *". */
        fun parseContentRange(header: String?): Pair<Long, Long?>? {
            val match = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""").matchEntire(header?.trim() ?: return null) ?: return null
            return match.groupValues[1].toLong() to match.groupValues[3].toLongOrNull()
        }
    }
}
