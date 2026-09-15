package ai.localstudio.app.vosk

import ai.localstudio.core.audio.AudioSource
import ai.localstudio.core.model.Transcript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk ASR spike (docs/14-vosk-spike.md): a minimal, standalone adapter
 * around `com.alphacephei:vosk-android`'s [Model]/[Recognizer], feeding an
 * existing [AudioSource] (in practice
 * [ai.localstudio.app.whisper.MicrophoneAudioSource], unchanged — same
 * AudioRecord/16kHz-mono capture the Whisper mic path already uses)
 * straight into [Recognizer.acceptWaveForm] one chunk at a time, so
 * partial/final text is available live rather than only after the whole
 * utterance is captured.
 *
 * Deliberately **not** routed through
 * [ai.localstudio.core.runtime.StreamingSpeechSession] or the paused
 * `SpeechRecognizer` interface (see the Phase 4 write-up in
 * docs/13-asr-pipeline-migration.md) — this is a separate, isolated path so
 * trying Vosk can't regress anything in the whisper.cpp pipeline. Model/
 * Recognizer lifecycle and Vosk's own JSON result format are both fully
 * contained here; callers only ever see [Transcript], same as every other
 * speech-to-text producer in this app.
 */
class VoskSpeechRecognizer {

    private var model: Model? = null
    private var loadedModelPath: String? = null
    private var micJob: Job? = null

    /** Read from the mic-read coroutine's own thread, written from the caller's (main) thread by [finish]/[cancel] — see their own doc comments for why this needs to be visible across threads without a full lock. */
    @Volatile
    private var finishRequested = false

    val isLoaded: Boolean get() = model != null

    /** Whether a session is currently running — a model may still be resident (see [isLoaded]) after this goes false. */
    val isActive: Boolean get() = micJob != null

    /**
     * Set when [start]'s mic-read loop ends because [AudioSource.stream]
     * itself threw, not because the caller asked it to stop via
     * [finish]/[cancel] — same side channel as
     * [ai.localstudio.app.whisper.WhisperCppMicSession.lastError], and for
     * the same reason: there is no other way to tell "the session died" from
     * "finish()/cancel() ended it normally" once the Flow [start] returned
     * has completed. Cleared at the start of every [start] call.
     */
    var lastError: Throwable? = null
        private set

    /** Loads (or reuses) the Vosk model directory at [modelPath] — see [VoskModelStore]. `Model(String)` is a blocking native call, so this always runs on [Dispatchers.IO]. */
    private suspend fun ensureLoaded(modelPath: String): Model = withContext(Dispatchers.IO) {
        model?.let { if (loadedModelPath == modelPath) return@withContext it }
        model?.close()
        Model(modelPath).also {
            model = it
            loadedModelPath = modelPath
        }
    }

    /**
     * Loads the model at [modelPath] if needed, creates a fresh [Recognizer]
     * against it, and pumps [source] into [Recognizer.acceptWaveForm] on a
     * background coroutine. Returns a [Flow] of [Transcript] — collect it
     * for live text. Every emission carries the *full* text accumulated so
     * far this session (whatever Vosk has already finalized, plus the
     * current in-progress partial appended after it), so a UI can just set
     * a TextView to `transcript.text` on every emission with no revision
     * bookkeeping of its own — unlike the startMs-matching
     * [ai.localstudio.app.whisper.WhisperCppMicSession] needs, since Vosk's
     * own endpointer already tells partial and final apart via
     * [Recognizer.acceptWaveForm]'s own return value.
     *
     * Calling this again while already active implicitly [cancel]s the
     * previous session first, same as [WhisperCppMicSession.start].
     */
    suspend fun start(modelPath: String, source: AudioSource, sampleRate: Float = SAMPLE_RATE_HZ): Flow<Transcript> {
        cancel()
        lastError = null
        finishRequested = false
        val loadedModel = ensureLoaded(modelPath)
        val recognizer = Recognizer(loadedModel, sampleRate)

        val channel = Channel<Transcript>(Channel.UNLIMITED)
        var settledText = ""

        fun appendSettled(resultJson: String) {
            val text = jsonField(resultJson, "text")
            if (text.isNotBlank()) settledText = "$settledText $text".trim()
        }

        micJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                source.stream { chunk ->
                    val displayText = if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                        appendSettled(recognizer.result)
                        settledText
                    } else {
                        val partial = jsonField(recognizer.partialResult, "partial")
                        if (partial.isBlank()) settledText else "$settledText $partial".trim()
                    }
                    channel.trySend(Transcript(text = displayText))
                }
                // source.stream() returning on its own (rather than being
                // cancelled) means the mic loop's own read failed
                // internally — see MicrophoneAudioSource's catch/break —
                // which is the same "no more audio" signal finish() below
                // is, so flush the same way.
                appendSettled(recognizer.finalResult)
                channel.trySend(Transcript(text = settledText))
                channel.close()
            } catch (e: CancellationException) {
                // finish() flushes the last buffered utterance via
                // getFinalResult() before the Flow completes; cancel()
                // leaves finishRequested false and just discards it — same
                // finish()-vs-cancel() split as WhisperCppMicSession.
                if (finishRequested) {
                    appendSettled(recognizer.finalResult)
                    channel.trySend(Transcript(text = settledText))
                }
                channel.close()
                throw e
            } catch (e: Exception) {
                lastError = e
                channel.close(e)
            } finally {
                recognizer.close()
            }
        }
        return channel.receiveAsFlow()
    }

    /** Graceful stop: flushes whatever utterance is still buffered via [Recognizer.getFinalResult] — still delivered through the Flow [start] returned. */
    fun finish() {
        finishRequested = true
        micJob?.cancel()
        micJob = null
    }

    /** Immediate stop, discarding anything not yet finalized. */
    fun cancel() {
        finishRequested = false
        micJob?.cancel()
        micJob = null
    }

    /** Frees the loaded model. Call when the feature using this is done, not between recordings — [start] reuses it. */
    fun release() {
        cancel()
        model?.close()
        model = null
        loadedModelPath = null
    }

    private fun jsonField(json: String, field: String): String =
        runCatching { JSONObject(json).optString(field, "") }.getOrDefault("")

    companion object {
        private const val SAMPLE_RATE_HZ = 16_000f
    }
}
