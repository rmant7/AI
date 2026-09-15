package ai.localstudio.app.vosk

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.StreamingSpeechSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * [SpeechModelHandle] over an already-loaded Vosk [model] — the router-
 * compatible counterpart to [VoskSpeechRecognizer], which instead drives
 * its own [ai.localstudio.core.audio.AudioSource] pull-style (built for
 * the standalone Vosk spike — see docs/14-vosk-spike.md — before this
 * router existed). This class is push-based: whoever holds the
 * [StreamingSpeechSession] calls `acceptAudio`, matching
 * [SpeechModelHandle]/[StreamingSpeechSession] exactly, since a
 * [ai.localstudio.core.speech.StreamingSpeechRouter] session is itself
 * what drives audio into whichever model is currently selected.
 *
 * [transcribe] is deliberately unsupported — file transcription was never
 * built for Vosk (see [SpeechModelCapabilities.supportsFileTranscription]
 * on the registered model, which is `false`; a caller that checked
 * capabilities first never reaches this).
 */
class VoskSpeechModel(
    override val modelId: String,
    private val model: Model,
    override val ramBytes: Long,
) : SpeechModelHandle {

    override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
        throw UnsupportedOperationException("$modelId does not support file transcription — see its SpeechModelCapabilities")

    override fun startStreaming(language: String?): StreamingSpeechSession {
        val recognizer = Recognizer(model, SAMPLE_RATE_HZ)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val inbox = Channel<ShortArray>(Channel.UNLIMITED)
        val channel = Channel<TranscriptSegment>(Channel.UNLIMITED)

        // utteranceStartMs advances only when Vosk's own endpointer settles
        // an utterance — see StreamingSpeechSession.segments' own contract:
        // same startMs across emissions means "revise this utterance in
        // place", a changed one means "the previous utterance settled".
        // totalSamples/utteranceStartSamples track wall-clock position
        // across the whole session, not just the current utterance.
        var utteranceStartSamples = 0L
        var totalSamples = 0L

        val processor = scope.launch {
            try {
                for (chunk in inbox) {
                    val settled = recognizer.acceptWaveForm(chunk, chunk.size)
                    totalSamples += chunk.size
                    val startMs = utteranceStartSamples * 1000L / SAMPLE_RATE_HZ.toLong()
                    val endMs = totalSamples * 1000L / SAMPLE_RATE_HZ.toLong()
                    if (settled) {
                        val text = jsonField(recognizer.result, "text")
                        if (text.isNotBlank()) channel.trySend(TranscriptSegment(text = text, startMs = startMs, endMs = endMs))
                        utteranceStartSamples = totalSamples
                    } else {
                        val partial = jsonField(recognizer.partialResult, "partial")
                        if (partial.isNotBlank()) channel.trySend(TranscriptSegment(text = partial, startMs = startMs, endMs = endMs))
                    }
                }
                val finalText = jsonField(recognizer.finalResult, "text")
                if (finalText.isNotBlank()) {
                    val startMs = utteranceStartSamples * 1000L / SAMPLE_RATE_HZ.toLong()
                    val endMs = totalSamples * 1000L / SAMPLE_RATE_HZ.toLong()
                    channel.trySend(TranscriptSegment(text = finalText, startMs = startMs, endMs = endMs))
                }
            } finally {
                recognizer.close()
                channel.close()
            }
        }

        return object : StreamingSpeechSession {
            override val segments: Flow<TranscriptSegment> = channel.receiveAsFlow()
            override fun acceptAudio(pcm: ShortArray) {
                inbox.trySend(pcm)
            }

            override fun finish() {
                inbox.close()
            }

            override fun cancel() {
                processor.cancel()
                inbox.close()
                channel.close()
            }
        }
    }

    override fun requestCancel() = Unit

    override fun close() {
        model.close()
    }

    private fun jsonField(json: String, field: String): String =
        runCatching { JSONObject(json).optString(field, "") }.getOrDefault("")

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000f
    }
}
