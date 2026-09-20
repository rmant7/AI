package ai.localstudio.app.vosk

import ai.localstudio.app.whisper.MediaCodecAudioSource
import ai.localstudio.core.model.Transcript
import android.content.Context
import android.net.Uri

/**
 * File-transcription counterpart to [ai.localstudio.app.whisper.WhisperFileTranscriber]
 * — same role ([ai.localstudio.app.whisper.FileTranscriptionRunner] drives
 * both engine-agnostically), but Vosk has no equivalent of whisper.cpp's own
 * file decode: it only ever consumed a live [ai.localstudio.core.audio.AudioSource]
 * ([VoskSpeechRecognizer.start] was built for [ai.localstudio.app.whisper.MicrophoneAudioSource]).
 * [MediaCodecAudioSource] already implements that exact same interface —
 * the decode side neither knows nor cares whether chunks come from a mic or
 * a file — so a file transcription here is just [VoskSpeechRecognizer]
 * fed a different [ai.localstudio.core.audio.AudioSource]. When
 * [MediaCodecAudioSource.stream] returns on its own (the file ended, not a
 * cancellation), [VoskSpeechRecognizer] already flushes the final result
 * and closes its own flow — exactly the "done" signal this needs, with
 * nothing file-transcription-specific to add on top.
 *
 * Uses its own [VoskSpeechRecognizer] instance, separate from
 * [ai.localstudio.app.AppContainer.voskRecognizer] (the live-mic one) — same
 * reasoning [WhisperFileTranscriber] and
 * [ai.localstudio.app.whisper.WhisperCppMicSession] already split for
 * Whisper: a file transcription running in the background must not
 * `cancel()` an unrelated live-mic session just because both would
 * otherwise share one loaded model.
 */
class VoskFileTranscriber(
    private val context: Context,
    private val recognizer: VoskSpeechRecognizer,
) {

    /** Whether a model is currently resident — same purpose as [ai.localstudio.app.whisper.WhisperFileTranscriber.isLoaded]. */
    val isLoaded: Boolean get() = recognizer.isLoaded

    /**
     * Transcribes [uri] against [seed]. [onSegment] fires on every emission
     * — each one already carries the *full* text accumulated so far (see
     * [VoskSpeechRecognizer.start]'s own doc comment), so the caller can
     * just replace its running text with whatever this reports most
     * recently, same as [ai.localstudio.app.whisper.WhisperFileTranscriber]'s
     * per-segment callback.
     */
    suspend fun transcribe(uri: Uri, seed: VoskModelSeed, onSegment: (String) -> Unit): Transcript {
        val modelDir = VoskModelStore.modelDir(context, seed)
        val source = MediaCodecAudioSource.forUri(context, uri)
        var lastText = ""
        recognizer.start(modelDir.absolutePath, source).collect { transcript ->
            lastText = transcript.text
            onSegment(lastText)
        }
        return Transcript(text = lastText)
    }

    /** Interrupts whichever file is transcribing right now — see [VoskSpeechRecognizer.cancel]'s own doc comment. */
    fun requestCancel() {
        recognizer.cancel()
    }

    /** Frees the loaded model. */
    fun release() {
        recognizer.release()
    }
}
