package ai.localstudio.app.whisper

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import android.net.Uri

/**
 * Loads a whisper.cpp model once and transcribes any number of files against
 * it — the vertical-slice test harness for [WhisperCppRuntime]/
 * [WhisperCppSpeechModel] (see [ai.localstudio.app.TranscribeActivity] and
 * docs/13-asr-pipeline-migration.md). Doesn't go through
 * [ai.localstudio.core.runtime.RuntimeManager]: this is a standalone test
 * screen, not a pipeline run, the same way [ai.localstudio.app.ChatActivity]'s
 * mic button already talks to [WhisperEngine] directly rather than through
 * the router.
 */
class WhisperFileTranscriber(
    private val runtime: WhisperCppRuntime,
    private val whisperStore: WhisperStore,
) {
    private var loadedSeedId: String? = null
    private var loaded: WhisperCppSpeechModel? = null

    /** Whether a model is currently resident — checked before [release] purely for a meaningful log line, not correctness ([release] is a safe no-op either way). */
    val isLoaded: Boolean get() = loaded != null

    /** Loads [seed] if it isn't already the one resident — reused across every subsequent call, same file or not. */
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
     * Transcribes [uri] against [seed], loading the model first if needed.
     * [onSegment] fires as soon as whisper.cpp finalizes each segment — before
     * the rest of the file has even finished decoding — which is what the
     * caller uses to update a result row incrementally instead of only at the
     * very end.
     */
    suspend fun transcribe(
        uri: Uri,
        seed: WhisperModelSeed,
        language: String?,
        onSegment: (TranscriptSegment) -> Unit,
    ): Transcript {
        val handle = ensureLoaded(seed)
        return handle.transcribeStreaming(AudioRef(uri = uri.toString()), language, onSegment)
    }

    /** Interrupts whichever file is transcribing right now; the model stays loaded for the next one. */
    fun requestCancel() {
        loaded?.requestCancel()
    }

    /**
     * Frees the native model — safe to call even while a [transcribe] is in
     * flight on another coroutine (e.g. [ai.localstudio.app.AppContainer]'s
     * memory-pressure handler, concurrently with
     * [ai.localstudio.app.TranscribeActivity]'s own): [WhisperCppSpeechModel.close]
     * itself now waits for any native call already in progress before
     * freeing (it acquires the same [ai.localstudio.whisper.WhisperBridge.nativeOpMutex]
     * every native call goes through). [requestCancel] first so that wait is
     * short instead of running the in-flight window to completion — not a
     * requirement for correctness anymore, just for promptness.
     */
    fun release() {
        loaded?.requestCancel()
        loaded?.close()
        loaded = null
        loadedSeedId = null
    }
}
