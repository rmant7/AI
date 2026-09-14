package ai.localstudio.core.audio

/**
 * Pure sample-rate/channel-count conversion, split out of the Android decoder
 * ([ai.localstudio.app.whisper.MediaCodecAudioSource]) so it is testable on a
 * plain JVM instead of only on a device — the same reasoning as
 * [UtteranceAccumulator]. Ported from the working implementation in
 * `rmant7/claude-code`'s `claude/android-whisper-transcription-3tuggu` branch
 * (`AudioDecoder.kt`); see docs/13-asr-pipeline-migration.md.
 */
object PcmMath {

    /** Averages [channels] interleaved channels down to one. [pcm].size must be a multiple of [channels]. */
    fun downmixToMono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        val frames = pcm.size / channels
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    /**
     * Linear-interpolation resample. Good enough for speech going into
     * whisper.cpp (which itself works on a 25ms/10ms-hop mel spectrogram, far
     * coarser than any resampling artifact this introduces) without pulling in
     * a dedicated resampling library for a mobile JNI build.
     */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputLength = (input.size * ratio).toInt()
        val output = ShortArray(outputLength)
        for (i in output.indices) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a = input.getOrElse(idx) { 0 }
            val b = input.getOrElse(idx + 1) { a }
            output[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return output
    }
}
