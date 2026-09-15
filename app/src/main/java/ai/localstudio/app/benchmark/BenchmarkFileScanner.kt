package ai.localstudio.app.benchmark

import ai.localstudio.app.whisper.MediaFileUtils
import ai.localstudio.core.benchmark.BenchmarkAudioFile
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Finds candidate files and reads the per-file metadata (size, duration,
 * sample rate, channels) [ai.localstudio.core.benchmark.BenchmarkAudioFile]
 * carries — gathered once up front, before any engine runs, precisely so
 * every engine is measured against the same recorded numbers rather than
 * each independently reporting whatever it happens to observe about its
 * own input.
 */
object BenchmarkFileScanner {

    /** Recognized extensions: [ai.localstudio.app.whisper.MediaFileUtils]'s own set already covers wav/mp3/m4a/ogg/flac/etc — reused rather than duplicated. */
    fun scan(context: Context, root: DocumentFile): List<BenchmarkAudioFile> =
        MediaFileUtils.listMediaFilesRecursively(root).map { doc -> toBenchmarkAudioFile(context, doc) }

    private fun toBenchmarkAudioFile(context: Context, doc: DocumentFile): BenchmarkAudioFile {
        val (durationMs, sampleRateHz, channels) = readAudioTrackMeta(context, doc.uri)
        return BenchmarkAudioFile(
            uri = doc.uri.toString(),
            fileName = doc.name ?: doc.uri.toString(),
            fileSizeBytes = doc.length(),
            durationMs = durationMs,
            sampleRateHz = sampleRateHz,
            channels = channels,
        )
    }

    /** Best-effort: a file MediaExtractor can't parse (corrupt, unsupported container) yields all-null metadata rather than failing the whole scan — it still shows up as a candidate, and the benchmark run itself will report the real failure when it actually tries to transcribe it. */
    private fun readAudioTrackMeta(context: Context, uri: Uri): Triple<Long?, Int?, Int?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var durationMs: Long? = null
            var sampleRateHz: Int? = null
            var channels: Int? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                break
            }
            Triple(durationMs, sampleRateHz, channels)
        } catch (e: Exception) {
            Triple(null, null, null)
        } finally {
            extractor.release()
        }
    }
}
