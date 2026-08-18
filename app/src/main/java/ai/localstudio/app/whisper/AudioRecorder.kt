package ai.localstudio.app.whisper

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import ai.localstudio.core.audio.UtteranceAccumulator
import ai.localstudio.core.audio.UtteranceConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records mono 16kHz PCM through a [UtteranceAccumulator] instead of a flat
 * buffer — see docs/12-audio.md, which this previously ignored entirely.
 *
 * Two things that mattered enough to be worth the rewrite:
 * - **Leading silence is dropped before it ever reaches Whisper.** A live
 *   preview snapshot taken a second into recording used to be mostly (or
 *   entirely) silence, which is exactly what breaks Whisper's language
 *   detection — a snippet that's mostly quiet gets guessed into a wrong
 *   language, which is what "Chinese text, then it corrects to English"
 *   looked like.
 * - **[shouldFinalize] exposes the documented pause-based turn-taking**
 *   (0.8s of silence after speech, or the 25s window filling up) so the
 *   caller can auto-stop instead of only ever waiting for a manual tap.
 */
class AudioRecorder(private val config: UtteranceConfig = UtteranceConfig()) {

    @Volatile
    private var recording = false
    private var thread: Thread? = null
    private val lock = Any()
    private var accumulator = UtteranceAccumulator(config)

    val isRecording: Boolean get() = recording

    /** True once the speaker has paused, or the window is full — the cue to auto-finalize. */
    val shouldFinalize: Boolean get() = synchronized(lock) { accumulator.shouldFinalize }

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        synchronized(lock) { accumulator = UtteranceAccumulator(config) }
        recording = true

        thread = Thread {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                config.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            // 8x the platform minimum: inference runs on a different thread
            // and can stall this one's consumer; a thin ring buffer means
            // the oldest audio is silently overwritten rather than read —
            // "recording works" with no words in it.
            val bufferSizeBytes = (minBufferBytes * 8).coerceAtLeast(minBufferBytes)
            // docs/12-audio.md recommends VOICE_RECOGNITION over MIC (it
            // skips phone-call AGC/noise suppression that hurts recognition)
            // — but on this device it produced a signal the relative-
            // threshold VAD never classified as voiced at all: nothing was
            // recognized, and the 0.8s pause path never fired because it
            // requires voiced samples first (only the 25s hard cap could
            // stop it, matching "very long pause before it turned off").
            // Reverting to MIC, which at least produced a signal — noisy,
            // but present — before VOICE_RECOGNITION was tried.
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes,
            )
            // ~0.25s per block: frequent enough for the VAD to track a real
            // onset/offset instead of averaging speech and silence together.
            val chunk = ShortArray((config.sampleRate / 4).coerceAtLeast(1))
            try {
                record.startRecording()
                while (recording) {
                    val read = try {
                        record.read(chunk, 0, chunk.size)
                    } catch (e: Exception) {
                        // Reading from a released/invalid AudioRecord throws
                        // on some devices; letting that propagate uncaught
                        // out of this thread crashes the whole process, not
                        // just this recording.
                        Log.e("AudioRecorder", "read failed, stopping", e)
                        break
                    }
                    if (read > 0) {
                        val block = FloatArray(read) { chunk[it] / 32768f }
                        synchronized(lock) { accumulator.append(block) }
                    }
                }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }.apply { start() }
    }

    /** Stops recording and returns the finished utterance as 16kHz mono PCM16. */
    fun stop(): ByteArray {
        recording = false
        thread?.join(STOP_JOIN_TIMEOUT_MS)
        thread = null
        return synchronized(lock) { pcm16Of(accumulator.takeUtterance()) }
    }

    /** Everything buffered so far — voice only, leading silence already excluded — for a live preview. */
    fun snapshot(): ByteArray = synchronized(lock) { pcm16Of(accumulator.snapshot()) }

    private fun pcm16Of(samples: FloatArray): ByteArray {
        val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = (sample * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(clamped.toShort())
        }
        return out.array()
    }

    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 2_000L
    }
}
