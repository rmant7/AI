package ai.localstudio.app.whisper

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.speech.AsrEngineType
import ai.localstudio.core.speech.RegisteredSpeechModel
import ai.localstudio.core.speech.SpeechModelCapabilities
import ai.localstudio.core.speech.SpeechModelInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whisper as the router's multilingual fallback — [RegisteredSpeechModel]
 * declares no fixed [Language][ai.localstudio.core.speech.Language] set
 * (empty = "any"), so [ai.localstudio.core.speech.SpeechModelRegistry]
 * matches it for whatever a specialist doesn't cover. Loading logic
 * mirrors [WhisperCppMicSession.ensureLoaded] — same reload-on-seed-change,
 * reuse-otherwise policy — kept as its own small copy rather than shared,
 * since the two now implement genuinely different interfaces
 * ([RegisteredSpeechModel] vs. this app's own mic-session API) and forcing
 * one to depend on the other's shape would be the wrong coupling for a
 * few duplicated lines.
 *
 * [SpeechModelCapabilities.supportsCodeSwitching] is `false` — Whisper's
 * shared multilingual vocabulary lets it *degrade gracefully* on code-
 * switched speech, which is not the same as a verified claim that it
 * stays coherent across a language switch. Not claimed until actually
 * tested (see docs/15-speech-routing.md).
 */
class WhisperRegisteredSpeechModel(
    private val runtime: WhisperCppRuntime,
    private val whisperStore: WhisperStore,
    private val seedProvider: () -> WhisperModelSeed?,
    id: String = "whisper-fallback",
    /** Below every specialist regardless of this number — SpeechModelRegistry.findCandidates ranks a language-matching specialist ahead of any empty-language generalist first. This only matters if two generalists are ever registered at once. */
    priority: Int = 0,
) : RegisteredSpeechModel {

    override val info = SpeechModelInfo(
        id = id,
        displayName = "Whisper (multilingual fallback)",
        capabilities = SpeechModelCapabilities(
            languages = emptySet(),
            supportsStreaming = true,
            supportsFileTranscription = true,
            supportsLanguageAutoDetection = true,
            supportsCodeSwitching = false,
            engine = AsrEngineType.WHISPER,
        ),
        priority = priority,
    )

    private var loadedSeedId: String? = null
    private var loaded: WhisperCppSpeechModel? = null
    private val loadMutex = Mutex()

    override suspend fun handle(): SpeechModelHandle = loadMutex.withLock {
        val seed = seedProvider() ?: error("No Whisper model installed")
        loaded?.let { if (loadedSeedId == seed.id) return@withLock it }
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
        handle
    }

    /** For [WhisperLanguageIdentifier], which needs the same already-loaded handle rather than triggering a second, independent load. Internal, not public: [WhisperCppSpeechModel] itself is `internal`, so a public signature exposing it would leak past this module's own boundary. */
    internal suspend fun loadedWhisperModel(): WhisperCppSpeechModel = handle() as WhisperCppSpeechModel

    val isLoaded: Boolean get() = loaded != null

    /**
     * Frees the loaded model under memory pressure — same gap this app
     * already fixed twice for [WhisperFileTranscriber]/[WhisperCppMicSession]:
     * a distinct native handle left resident and uncounted is exactly what
     * an OOM kill with "everything else correctly unloaded" looked like in
     * this app's own log (see [ai.localstudio.app.AppContainer.releaseWhisperEngines]'s
     * own doc comment). Non-suspend and unguarded by [loadMutex], same
     * accepted convention as [ai.localstudio.app.whisper.WhisperCppMicSession.release]/
     * [ai.localstudio.app.vosk.VoskSpeechRecognizer.release] — a release
     * racing an in-flight [handle] load is the same known, accepted risk
     * those already carry, not a new one; the native close/free path is
     * what's actually synchronized (see [WhisperCppSpeechModel]'s own
     * doc comment on why its own `close()` is safe against a concurrent
     * in-flight call).
     */
    fun release() {
        loaded?.close()
        loaded = null
        loadedSeedId = null
    }
}
