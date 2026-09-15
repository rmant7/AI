package ai.localstudio.app.whisper

import ai.localstudio.core.audio.AudioSource
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The microphone as an [AudioSource] — mono 16kHz PCM16, same shape
 * [MediaCodecAudioSource] streams from a file, so a
 * [ai.localstudio.core.runtime.StreamingSpeechSession] downstream doesn't
 * know or care which one is feeding it (see docs/13-asr-pipeline-migration.md,
 * Phase 3). Capture parameters are ported from the already-working
 * [AudioRecorder] rather than re-derived: `MIC` (not `VOICE_RECOGNITION` —
 * see that class's own doc comment for the device that produced a signal
 * the VAD never classified as voiced under `VOICE_RECOGNITION`), an 8x
 * buffer so inference-side stalls on another thread don't overwrite
 * unread audio, ~0.25s read blocks.
 *
 * Unlike [AudioRecorder] this class does no VAD/accumulation of its own —
 * it only produces chunks. [WhisperCppSpeechModel.startStreaming]'s
 * session (or, in the future, a real incremental engine) is what decides
 * what to do with them, the same separation [AudioSource] already
 * enforces for files.
 */
class MicrophoneAudioSource(private val sampleRate: Int = 16_000) : AudioSource {

    @SuppressLint("MissingPermission") // caller's responsibility, same contract as AudioRecorder.start()
    override suspend fun stream(onChunk: suspend (ShortArray) -> Unit) = withContext(Dispatchers.IO) {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSizeBytes = (minBufferBytes * 8).coerceAtLeast(minBufferBytes)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes,
        )
        val chunk = ShortArray((sampleRate / 4).coerceAtLeast(1))
        try {
            record.startRecording()
            while (true) {
                currentCoroutineContext().ensureActive() // stop() is a coroutine cancellation, not a flag this loop polls itself
                val read = try {
                    record.read(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    // Same hazard AudioRecorder.start() guards against: a
                    // concurrent release() of this AudioRecord from another
                    // thread throws mid-read on some devices, and letting
                    // that escape uncaught here would crash the whole
                    // process rather than just end this stream.
                    Log.e("MicrophoneAudioSource", "read failed, stopping", e)
                    break
                }
                if (read > 0) {
                    onChunk(if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read))
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }
}
