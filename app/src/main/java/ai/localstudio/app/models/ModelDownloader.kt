package ai.localstudio.app.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class DownloadProgress(val bytesDownloaded: Long, val bytesTotal: Long) {
    val fraction: Float get() = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal else 0f
}

/**
 * Resumable download that survives what actually goes wrong on a phone.
 *
 * Three things had to be handled explicitly, and the first one is why a
 * download could fail outright:
 *
 * 1. **Redirects are followed by hand.** `HttpURLConnection` silently refuses
 *    to follow a redirect that changes protocol or host — and every model host
 *    redirects the download to a CDN on another host. Left to the platform, the
 *    request returns a 302 body of a few hundred bytes and the "model" is
 *    unusable.
 * 2. **Resumption**, because a multi-gigabyte transfer over mobile data will be
 *    interrupted: bytes go to a `.part` file and continue via HTTP `Range`.
 * 3. **Backoff that resets on progress**, so a flaky network cannot cancel a
 *    download that is in fact advancing; only attempts that transfer nothing
 *    count towards giving up.
 */
class ModelDownloader(
    private val maxConsecutiveFailures: Int = 6,
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
    private val maxRedirects: Int = 5,
) {

    fun interface Listener {
        fun onProgress(progress: DownloadProgress)
    }

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun download(url: String, destination: File, tempFile: File, listener: Listener) {
        cancelled = false
        var failures = 0
        var backoff = initialBackoffMs
        var lastError: IOException = IOException("Загрузка не началась")

        while (failures < maxConsecutiveFailures) {
            if (cancelled) throw IOException("Загрузка отменена")
            val before = if (tempFile.exists()) tempFile.length() else 0L
            try {
                downloadOnce(url, tempFile, listener)
                destination.delete()
                if (!tempFile.renameTo(destination)) throw IOException("Не удалось сохранить файл")
                return
            } catch (e: IOException) {
                if (cancelled) throw e
                lastError = e
                val progressed = tempFile.exists() && tempFile.length() > before
                failures = if (progressed) 0 else failures + 1
                if (failures >= maxConsecutiveFailures) break
                Thread.sleep(backoff)
                backoff = if (progressed) initialBackoffMs else (backoff * 2).coerceAtMost(maxBackoffMs)
            }
        }
        throw IOException("${lastError.message} (после $maxConsecutiveFailures попыток)", lastError)
    }

    private fun downloadOnce(url: String, tempFile: File, listener: Listener) {
        val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
        val connection = open(url, resumeFrom)

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Сервер ответил HTTP $status${describe(connection)}")
            }

            // A 200 in reply to a Range request means the server ignored it and
            // is sending the whole file: appending would corrupt what is on disk.
            val append = status == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (!append && resumeFrom > 0) tempFile.delete()

            val alreadyHave = if (append) resumeFrom else 0L
            val remaining = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total = alreadyHave + remaining

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = alreadyHave
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) throw IOException("Загрузка отменена")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= REPORT_EVERY) {
                            lastReport = written
                            listener.onProgress(DownloadProgress(written, total))
                        }
                    }
                    output.flush()
                    listener.onProgress(DownloadProgress(written, total))
                }
            }

            if (total > 0 && tempFile.length() < total) {
                throw IOException("Передано ${tempFile.length()} из $total байт")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Follows redirects manually, carrying the Range header to the final host. */
    private fun open(startUrl: String, resumeFrom: Long): HttpURLConnection {
        var current = startUrl
        repeat(maxRedirects + 1) {
            val connection = (URI.create(current).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // Off on purpose: the platform drops cross-protocol redirects,
                // and it would also drop the Range header on the way.
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }

            val status = connection.responseCode
            if (status !in REDIRECT_CODES) return connection

            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw IOException("Редирект HTTP $status без адреса")
            current = URI.create(current).resolve(location).toString()
        }
        throw IOException("Слишком много редиректов")
    }

    private fun describe(connection: HttpURLConnection): String = runCatching {
        val message = connection.responseMessage.orEmpty()
        val body = connection.errorStream?.bufferedReader()?.use { it.readText().take(200) }.orEmpty()
        listOf(message, body).filter { it.isNotBlank() }.joinToString(": ").let { if (it.isBlank()) "" else " — $it" }
    }.getOrDefault("")

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
        const val REPORT_EVERY = 1L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
        const val USER_AGENT = "LocalAiStudio/0.1 (Android)"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
