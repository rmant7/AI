package ai.localstudio.app.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records mono 16kHz 16-bit PCM — exactly what [WhisperTranscriber] expects,
 * so no resampling step sits between microphone and model.
 */
class AudioRecorder {

    @Volatile
    private var recording = false
    private var thread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        buffer.reset()
        recording = true

        thread = Thread {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(SAMPLE_RATE / 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize,
            )
            val chunk = ByteArray(minBufferSize)
            try {
                record.startRecording()
                while (recording) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) synchronized(buffer) { buffer.write(chunk, 0, read) }
                }
            } finally {
                record.stop()
                record.release()
            }
        }.apply { start() }
    }

    /** Stops recording and returns everything captured, as raw 16kHz mono PCM16. */
    fun stop(): ByteArray {
        recording = false
        thread?.join(STOP_JOIN_TIMEOUT_MS)
        thread = null
        return synchronized(buffer) { buffer.toByteArray() }
    }

    /** Everything captured so far, without stopping — for a live preview while still recording. */
    fun snapshot(): ByteArray = synchronized(buffer) { buffer.toByteArray() }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
