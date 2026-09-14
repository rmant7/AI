package ai.localstudio.core.audio

import java.nio.ShortBuffer

/**
 * Growable PCM16 queue with cheap head removal, so a completed chunk can be
 * handed off without copying the not-yet-full remainder around on every
 * append. Shared by the file decoder
 * ([ai.localstudio.app.whisper.MediaCodecAudioSource], queue granularity —
 * seconds) and the whisper inference window
 * ([ai.localstudio.app.whisper.WhisperCppSpeechModel], whisper's own ~30s
 * analysis window) — same data structure, two very different chunk sizes.
 * Ported from `AudioDecoder.kt`'s private `PcmBuffer` in
 * `rmant7/claude-code`'s `claude/android-whisper-transcription-3tuggu`
 * branch; pulled up to `core` because it is plain array bookkeeping with no
 * Android dependency, so it can be tested here instead of only on a device.
 */
class PcmBuffer {
    private var data = ShortArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    val size: Int get() = end - start

    fun append(samples: ShortArray) {
        ensureRoom(samples.size)
        samples.copyInto(data, end)
        end += samples.size
    }

    fun append(source: ShortBuffer) {
        val count = source.remaining()
        ensureRoom(count)
        source.get(data, end, count)
        end += count
    }

    /** Removes and returns up to [count] samples from the head. */
    fun take(count: Int): ShortArray {
        val n = count.coerceAtMost(size)
        val out = ShortArray(n)
        data.copyInto(out, 0, start, start + n)
        start += n
        if (start == end) {
            start = 0
            end = 0
        }
        return out
    }

    /** Removes and returns everything currently buffered. */
    fun takeAll(): ShortArray = take(size)

    private fun ensureRoom(count: Int) {
        if (end + count <= data.size) return
        if (size + count <= data.size) {
            data.copyInto(data, 0, start, end)
        } else {
            var capacity = data.size
            while (capacity < size + count) capacity *= 2
            val grown = ShortArray(capacity)
            data.copyInto(grown, 0, start, end)
            data = grown
        }
        end = size
        start = 0
    }

    private companion object {
        const val INITIAL_CAPACITY = 1 shl 16
    }
}
