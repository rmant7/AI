package ai.localstudio.app.voicebenchmark

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * Records the reference voice for the Voice Benchmark as a mono 24 kHz
 * 16-bit WAV in app-private storage — the format TTS voice-cloning models
 * expect. Not the app's speech-recognition [ai.localstudio.app.whisper.AudioRecorder]:
 * that one is 16 kHz and trims silence for Whisper, both wrong for a
 * reference sample that should be kept exactly as spoken.
 *
 * Most phones cannot open an `AudioRecord` at 24 kHz directly, so it falls
 * back to 48 kHz and averages sample pairs down to 24 kHz.
 */
class ReferenceVoiceRecorder(context: Context) {

    val file: File = referenceFile(context)

    @Volatile
    private var recording = false
    private var thread: Thread? = null

    val isRecording: Boolean get() = recording
    val hasRecording: Boolean get() = file.exists() && file.length() > 44

    /** Length of the saved recording, or 0. */
    fun durationMs(): Long = if (hasRecording) WavFiles.durationMs(file) ?: 0L else 0L

    /**
     * Starts recording; returns false if no microphone configuration could be
     * opened. [onLevel] (0..1, ~20 Hz) and [onElapsed] are called from the
     * recording thread; [onFinished] once recording has stopped and the file
     * is written — including when it stops itself at [MAX_MS].
     */
    @SuppressLint("MissingPermission")
    fun start(onLevel: (Float) -> Unit, onElapsed: (Long) -> Unit, onFinished: () -> Unit): Boolean {
        if (recording) return true
        val (record, rate) = open() ?: return false
        recording = true
        thread = Thread({
            val pcm = ByteArrayOutputStream()
            val chunk = ShortArray(rate / 20)
            try {
                record.startRecording()
                var samples = 0L
                while (recording && samples * 1000 / OUTPUT_RATE < MAX_MS) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) break
                    val out = if (rate == OUTPUT_RATE) chunk.copyOf(read) else downsample(chunk, read)
                    var sum = 0.0
                    for (s in out) {
                        pcm.write(s.toInt() and 0xFF)
                        pcm.write((s.toInt() shr 8) and 0xFF)
                        sum += (s / 32768.0) * (s / 32768.0)
                    }
                    samples += out.size
                    onLevel(minOf(1f, (sqrt(sum / out.size) * 4.0).toFloat()))
                    onElapsed(samples * 1000 / OUTPUT_RATE)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                recording = false
                WavFiles.write(file, pcm.toByteArray(), OUTPUT_RATE)
                onFinished()
            }
        }, "reference-voice-recorder").also { it.start() }
        return true
    }

    /** Stops and waits for the file to be written. */
    fun stop() {
        recording = false
        thread?.join(2000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(OUTPUT_RATE, OUTPUT_RATE * 2)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2) * 2,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
            record.release()
        }
        return null
    }

    private fun downsample(input: ShortArray, count: Int): ShortArray =
        ShortArray(count / 2) { ((input[it * 2] + input[it * 2 + 1]) / 2).toShort() }

    companion object {
        /** The reference recording, also used by the avatar's cloned voice. */
        fun referenceFile(context: Context): File =
            File(File(context.applicationContext.filesDir, "voice_benchmark").also { it.mkdirs() }, "reference.wav")

        const val OUTPUT_RATE = 24_000
        const val MAX_MS = 30_000L
    }
}
