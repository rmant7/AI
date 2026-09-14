package ai.localstudio.core.audio

/**
 * Produces mono 16kHz PCM16, incrementally.
 *
 * Implemented once per platform capture/decode mechanism — Android
 * MediaExtractor+MediaCodec for a file or URL
 * (`ai.localstudio.app.whisper.MediaCodecAudioSource`), AudioRecord for the
 * microphone — so nothing above this interface (VAD, a [StreamingSpeechSession]
 * or [ai.localstudio.core.runtime.SpeechModelHandle].transcribe) knows or cares
 * which one produced a given chunk. See docs/13-asr-pipeline-migration.md.
 */
fun interface AudioSource {
    /**
     * Streams the source chunk by chunk, calling [onChunk] as each becomes
     * available. Must never decode/buffer the whole source before the first
     * call — that is the entire point of this interface over reading bytes
     * directly: an hour-long recording must not sit fully in RAM before
     * transcription can even start.
     */
    suspend fun stream(onChunk: suspend (ShortArray) -> Unit)
}
