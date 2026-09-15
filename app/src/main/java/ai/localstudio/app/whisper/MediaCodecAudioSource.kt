package ai.localstudio.app.whisper

import ai.localstudio.core.audio.AudioSource
import ai.localstudio.core.audio.PcmBuffer
import ai.localstudio.core.audio.PcmMath
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes an audio or video source into mono 16kHz PCM16 chunks, streaming —
 * the [AudioSource] Android provides via MediaExtractor/MediaCodec, which
 * natively demux most common containers (mp3, wav, m4a/aac, flac, ogg/opus,
 * amr, mp4, 3gp, webm/mkv, ...); for video only the audio track is selected
 * and decoded, video frames are never touched.
 *
 * Ported from the working implementation in `rmant7/claude-code`'s
 * `claude/android-whisper-transcription-3tuggu` branch (`AudioDecoder.kt`),
 * adapted to this repo's [AudioSource] abstraction: PCM16 chunks instead of
 * one flat float array, and the pure downmix/resample math moved to
 * [PcmMath] so it is unit-testable outside this Android-only class. See
 * docs/13-asr-pipeline-migration.md.
 *
 * Streaming is the entire point: PCM is handed to [stream]'s callback in
 * chunks as it is produced rather than accumulated into one array first, so
 * an hour-long recording never sits fully in RAM and a caller can start
 * inferring on chunk N while chunk N+1 is still decoding.
 */
class MediaCodecAudioSource private constructor(
    private val configureExtractor: (MediaExtractor) -> Unit,
    private val chunkSeconds: Int,
    private val onProgress: ((Int) -> Unit)?,
) : AudioSource {

    override suspend fun stream(onChunk: suspend (ShortArray) -> Unit) = withContext(Dispatchers.IO) {
        decodeStreaming(configureExtractor, chunkSeconds, onProgress) { chunk ->
            // decodeStreaming's own loop is synchronous MediaCodec/MediaExtractor
            // code, not itself suspending — runBlocking here just re-enters the
            // suspend world to deliver each chunk, and is where this producer
            // coroutine actually parks under a bounded consumer's backpressure
            // (see WhisperCppSpeechModel's decode channel).
            runBlocking { onChunk(chunk) }
        }
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000

        /** Small: this is queue granularity, not whisper's own analysis window — see WhisperCppSpeechModel. */
        const val DEFAULT_CHUNK_SECONDS = 1

        private const val TIMEOUT_US = 10_000L

        /** Streams [uri] (a content:// or file:// URI) as 16kHz mono PCM16 chunks. */
        fun forUri(
            context: Context,
            uri: Uri,
            chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
            onProgress: ((Int) -> Unit)? = null,
        ): MediaCodecAudioSource = MediaCodecAudioSource({ it.setDataSource(context, uri, null) }, chunkSeconds, onProgress)

        /**
         * Streams [source] — a local file path *or* an http(s) URL — as 16kHz
         * mono PCM16 chunks. For a URL, MediaExtractor fetches progressively,
         * so decoding starts almost immediately instead of after a full download.
         */
        fun forPathOrUrl(
            source: String,
            chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
            onProgress: ((Int) -> Unit)? = null,
        ): MediaCodecAudioSource = MediaCodecAudioSource({ it.setDataSource(source) }, chunkSeconds, onProgress)

        private suspend fun decodeStreaming(
            setSource: (MediaExtractor) -> Unit,
            chunkSeconds: Int,
            onProgress: ((Int) -> Unit)?,
            onChunk: (ShortArray) -> Unit,
        ) {
            val extractor = MediaExtractor()
            try {
                setSource(extractor)
            } catch (e: Exception) {
                extractor.release()
                // MediaExtractor's own message here is the near-useless "Failed to
                // instantiate extractor". By far the most common cause in practice
                // is being handed something that isn't a media stream at all — a
                // web page URL rather than a direct media link — so name that
                // explicitly.
                throw IOException(
                    "Could not read this as an audio/video stream. If this is a link, it must point " +
                        "directly at a media file, not at a web page that plays one.",
                    e,
                )
            }

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                throw IOException("No audio track found in this file")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sourceSampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            val sourceChannels = format.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L

            // Chunk size is measured in interleaved source-rate samples, and must
            // stay a whole number of frames so downmixing never splits a frame
            // across two chunks.
            val chunkFrames = chunkSeconds.coerceAtLeast(1) * sourceSampleRate
            val chunkSamples = chunkFrames * sourceChannels

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pending = PcmBuffer()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var lastReportedPercent = -1
            var emittedAnything = false

            fun emit(sampleCount: Int) {
                if (sampleCount <= 0) return
                val raw = pending.take(sampleCount)
                val mono = PcmMath.downmixToMono(raw, sourceChannels)
                val resampled = PcmMath.resample(mono, sourceSampleRate, TARGET_SAMPLE_RATE)
                if (resampled.isNotEmpty()) {
                    emittedAnything = true
                    onChunk(resampled)
                }
            }

            try {
                while (!sawOutputEos) {
                    currentCoroutineContext().ensureActive() // a cancelled caller must stop this loop, not run it to completion

                    if (!sawInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            pending.append(shortBuffer)
                        }
                        if (durationUs > 0 && onProgress != null) {
                            val percent = ((bufferInfo.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }

                    while (pending.size >= chunkSamples) emit(chunkSamples)
                }
                // Whatever is left over after the last full chunk is still real
                // audio, so it gets its own (short) final chunk rather than being
                // dropped.
                emit(pending.size)
            } catch (e: MediaCodec.CodecException) {
                // Real device report: this exception's own message() is a blank
                // string, not null — Throwable.describeForUser()'s null-or-blank
                // fallback to toString() doesn't help either, since the default
                // toString() is just "<classname>: " with nothing useful after
                // it. The actual diagnostic (what went wrong, whether retrying
                // could work) lives in this class's own dedicated fields, not in
                // message — errorCode/diagnosticInfo are folded into a real
                // IOException message here so every catch site upstream that
                // already knows how to report an IOException gets it for free.
                throw IOException(
                    "MediaCodec error ${e.errorCode} (recoverable=${e.isRecoverable}, transient=${e.isTransient}): ${e.diagnosticInfo}",
                    e,
                )
            } finally {
                runCatching { codec.stop() }
                codec.release()
                extractor.release()
            }

            if (!emittedAnything) throw IOException("This file contained no decodable audio")
        }

        private fun MediaFormat.optInt(key: String, fallback: Int): Int =
            if (containsKey(key)) getInteger(key) else fallback
    }
}
