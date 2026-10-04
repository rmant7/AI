package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.SourceException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The one place this package opens HTTP connections. Redirects are followed
 * by hand so a Range header survives them (Hugging Face answers a `resolve`
 * URL with a redirect to its CDN) and so credentials never do: [bearer] is
 * sent only on the first request, and only when [sendBearer] says that URL's
 * host may see it — never to wherever a redirect points.
 */
internal object HttpConnections {

    const val USER_AGENT = "LocalAiStudio/0.1 (Android)"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    fun open(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bearer: String? = null,
        sendBearer: (URI) -> Boolean = { false },
    ): HttpURLConnection {
        var current = URI.create(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            val connection = try {
                (current.toURL().openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (hop == 0 && !bearer.isNullOrBlank() && sendBearer(current)) {
                        setRequestProperty("Authorization", "Bearer $bearer")
                    }
                }
            } catch (e: IOException) {
                throw classify(e)
            }
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw classify(e)
            }
            if (status !in REDIRECTS) {
                checkStatus(connection, status)
                return connection
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw SourceException(SourceException.Kind.OTHER, "HTTP $status redirect without a Location")
            current = current.resolve(location)
        }
        throw SourceException(SourceException.Kind.OTHER, "too many redirects from $url")
    }

    /** 2xx and 416 pass; everything else becomes the [SourceException] kind the transfer and resolver act on. */
    private fun checkStatus(connection: HttpURLConnection, status: Int) {
        if (status in 200..299 || status == 416) return
        val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText().take(200) } }.getOrNull().orEmpty()
        connection.disconnect()
        val message = "HTTP $status" + if (detail.isNotBlank()) " — $detail" else ""
        throw when (status) {
            401, 403 -> SourceException(SourceException.Kind.ACCESS_DENIED, "$message (gated repository: needs an accepted licence and a token)")
            404, 410 -> SourceException(SourceException.Kind.NOT_FOUND, message)
            // Server-side trouble and rate limiting are worth a retry, like a dropped connection.
            in 500..599, 408, 429 -> SourceException(SourceException.Kind.NETWORK, message)
            else -> SourceException(SourceException.Kind.OTHER, message)
        }
    }

    fun classify(e: IOException): IOException = when (e) {
        is SourceException -> e
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            SourceException(SourceException.Kind.NETWORK, e.message ?: e.javaClass.simpleName, e)
        is InterruptedIOException, is SSLException -> SourceException(SourceException.Kind.NETWORK, e.message ?: e.javaClass.simpleName, e)
        else -> e
    }
}
