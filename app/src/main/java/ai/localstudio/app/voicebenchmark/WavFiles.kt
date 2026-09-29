package ai.localstudio.app.voicebenchmark

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavFiles {

    /** Writes [pcm16] (little-endian signed 16-bit, [channels] interleaved) as a canonical 44-byte-header WAV. */
    fun write(file: File, pcm16: ByteArray, sampleRate: Int, channels: Int = 1) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm16.size).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
        header.putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        header.put("data".toByteArray()).putInt(pcm16.size)
        file.outputStream().use { it.write(header.array()); it.write(pcm16) }
    }

    /**
     * Copies the first [maxSeconds] of a mono 16-bit WAV this app wrote itself
     * (canonical 44-byte header, see [write]) to [dst]. Returns [src] unchanged
     * when it is already short enough.
     */
    fun trimmedCopy(src: File, dst: File, maxSeconds: Double): File {
        val bytes = src.readBytes()
        if (bytes.size < 44) return src
        val rate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val keep = (rate * 2L * maxSeconds).toLong().coerceAtLeast(2L) and 1L.inv()
        if (bytes.size - 44 <= keep) return src
        write(dst, bytes.copyOfRange(44, 44 + keep.toInt()), rate)
        return dst
    }

    /** Length of a PCM16 WAV in milliseconds, or null if it isn't one. Tolerates engines that leave the data size at 0. */
    fun durationMs(file: File): Long? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var sampleRate = 0
            var frameBytes = 0
            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunk)
                val id = String(chunk, 0, 4)
                val declared = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val body = raf.filePointer
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(16)
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        val channels = b.getShort(2).toInt()
                        sampleRate = b.getInt(4)
                        frameBytes = channels * (b.getShort(14).toInt() / 8)
                    }
                    "data" -> {
                        if (sampleRate <= 0 || frameBytes <= 0) return null
                        val available = raf.length() - body
                        val size = if (declared == 0L || declared > available) available else declared
                        return size / frameBytes * 1000L / sampleRate
                    }
                }
                raf.seek(body + declared + (declared and 1L))
            }
        }
        return null
    }
}
