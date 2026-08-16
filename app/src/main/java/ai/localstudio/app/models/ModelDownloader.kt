package ai.localstudio.app.models

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class DownloadProgress(val bytesDownloaded: Long, val bytesTotal: Long) {
    val fraction: Float get() = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal else 0f
}

/**
 * Resumable download.
 *
 * A multi-gigabyte transfer over mobile data has to survive a dropped
 * connection rather than start over, so bytes go to a `.part` file, resumption
 * uses HTTP `Range`, and backoff resets on any real progress: otherwise a bad
 * network cancels a download that is in fact advancing. The file is promoted to
 * its final name only once the transfer is complete.
 */
class ModelDownloader(
    private val maxConsecutiveFailures: Int = 5,
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
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
        throw lastError
    }

    private fun downloadOnce(url: String, tempFile: File, listener: Listener) {
        val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Сервер ответил HTTP $status")

            // 200 to a Range request means the server ignored it and is sending
            // the whole file: appending would corrupt what is already on disk.
            val append = status == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (!append && resumeFrom > 0) tempFile.delete()

            val alreadyHave = if (append) resumeFrom else 0L
            val remaining = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total = alreadyHave + remaining

            connection.inputStream.use { input ->
                java.io.FileOutputStream(tempFile, append).use { output ->
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
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
        const val REPORT_EVERY = 2L * 1024 * 1024
    }
}
