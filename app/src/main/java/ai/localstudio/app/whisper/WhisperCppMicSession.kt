package ai.localstudio.app.whisper

import ai.localstudio.core.audio.AudioSource
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.StreamingSpeechSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Drives a genuinely incremental [StreamingSpeechSession] from a live
 * [AudioSource] (in practice [MicrophoneAudioSource], but nothing here is
 * mic-specific) — Phase 3 of docs/13-asr-pipeline-migration.md, and the
 * first real caller of [ai.localstudio.core.runtime.SpeechModelHandle.startStreaming].
 *
 * Deliberately **not** routed through
 * [ai.localstudio.core.runtime.RuntimeManager]: same reasoning as
 * [WhisperFileTranscriber] — `RuntimeManager` is built around
 * acquire-use-release within one call (`withModel { }`), and a live
 * dictation session's lifetime is the opposite of that, open-ended and
 * driven by the user starting/stopping. This class owns its own
 * [WhisperCppSpeechModel] instance instead, exactly like
 * [WhisperFileTranscriber] and the existing [WhisperEngine] already do.
 * What makes that safe against freeing the model mid-utterance is
 * [WhisperCppSpeechModel.close] itself now waiting for any in-flight native
 * call before freeing — see that method's own doc comment.
 *
 * Does not touch [ai.localstudio.app.ChatActivity]'s existing mic button or
 * [WhisperEngine]/[AudioRecorder] at all: that path stays exactly as it is.
 * This is new, additive infrastructure for whoever wires up the next
 * real live-dictation UI, not a replacement forced onto the current one
 * sight unseen.
 */
class WhisperCppMicSession(
    private val runtime: WhisperCppRuntime,
    private val whisperStore: WhisperStore,
) {
    private var loadedSeedId: String? = null
    private var loaded: WhisperCppSpeechModel? = null
    private var activeSession: StreamingSpeechSession? = null
    private var micJob: Job? = null

    /** Whether a session is currently running — a model may still be resident (see [isLoaded]) after this goes false. */
    val isActive: Boolean get() = activeSession != null

    val isLoaded: Boolean get() = loaded != null

    private suspend fun ensureLoaded(seed: WhisperModelSeed): WhisperCppSpeechModel {
        loaded?.let { if (loadedSeedId == seed.id) return it }
        loaded?.close()

        val file = whisperStore.modelFile(seed)
        val descriptor = ModelDescriptor(
            id = seed.id,
            family = "whisper",
            version = "1",
            parameterCount = 1,
            capabilities = setOf(Capability.SPEECH_TO_TEXT),
            bindings = listOf(
                RuntimeBinding(
                    runtime = RuntimeKind.WHISPER_CPP,
                    artifact = file.absolutePath,
                    fileSizeBytes = file.length().coerceAtLeast(1),
                ),
            ),
        )
        val handle = runtime.load(descriptor, descriptor.bindings.first()) as WhisperCppSpeechModel
        loaded = handle
        loadedSeedId = seed.id
        return handle
    }

    /**
     * Loads [seed] if needed, starts a streaming session against it, and
     * pumps [source] into it on a background coroutine. Returns the
     * session's own segment [Flow] — collect it for live text. Calling this
     * again while already active implicitly [cancel]s the previous session
     * first (discarding whatever it hadn't finalized) rather than running
     * two at once against the same model.
     */
    suspend fun start(seed: WhisperModelSeed, source: AudioSource, language: String? = null): Flow<TranscriptSegment> {
        cancel()
        val handle = ensureLoaded(seed)
        val session = handle.startStreaming(language)
        activeSession = session

        micJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                source.stream { chunk -> session.acceptAudio(chunk) }
            } catch (e: CancellationException) {
                throw e
            }
            // A source finishing on its own (the mic loop only ever ends via
            // cancellation, but a hypothetical bounded AudioSource wouldn't)
            // is the same "no more audio" signal as the user releasing a
            // mic button — finalize whatever's buffered instead of just
            // dropping it.
            session.finish()
        }
        return session.segments
    }

    /** Graceful stop: flushes whatever utterance is still buffered — still delivered through the Flow [start] returned. */
    fun finish() {
        micJob?.cancel()
        micJob = null
        activeSession?.finish()
        activeSession = null
    }

    /** Immediate stop, discarding anything not yet finalized. */
    fun cancel() {
        micJob?.cancel()
        micJob = null
        activeSession?.cancel()
        activeSession = null
    }

    /** Frees the loaded model. Call when the feature using this is done, not between recordings — [start] reuses it. */
    fun release() {
        cancel()
        loaded?.requestCancel()
        loaded?.close()
        loaded = null
        loadedSeedId = null
    }
}
