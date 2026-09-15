package ai.localstudio.core.speech

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.core.runtime.StreamingSpeechSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Emits one fixed segment when [finish] is called — no real inference, just enough to prove the router dispatched to it. */
class FakeAsrModel(override val modelId: String, private val text: String = "hello from $modelId") : SpeechModelHandle {
    var acceptedChunkCount = 0
        private set
    var cancelled = false
        private set

    override val ramBytes: Long = 1

    override suspend fun transcribe(audio: AudioRef, language: String?): Transcript =
        Transcript(text = text, language = language)

    override fun startStreaming(language: String?): StreamingSpeechSession {
        val channel = Channel<TranscriptSegment>(Channel.UNLIMITED)
        return object : StreamingSpeechSession {
            override val segments: Flow<TranscriptSegment> = channel.receiveAsFlow()
            override fun acceptAudio(pcm: ShortArray) {
                acceptedChunkCount++
            }

            override fun finish() {
                channel.trySend(TranscriptSegment(text = text, startMs = 0, endMs = 0))
                channel.close()
            }

            override fun cancel() {
                cancelled = true
                channel.close()
            }
        }
    }

    override fun requestCancel() = Unit
    override fun close() = Unit
}

/** A [SpeechModelHandle] whose [startStreaming] always throws — simulates a specialist that fails to start. */
class BrokenAsrModel(override val modelId: String) : SpeechModelHandle {
    override val ramBytes: Long = 1
    override suspend fun transcribe(audio: AudioRef, language: String?): Transcript = throw IllegalStateException("$modelId is broken")
    override fun startStreaming(language: String?): StreamingSpeechSession = throw IllegalStateException("$modelId failed to start")
    override fun requestCancel() = Unit
    override fun close() = Unit
}

/**
 * [RegisteredSpeechModel] whose [handle] is a plain lambda — lets a test
 * construct a specialist, a fallback, or a "handle() itself throws"
 * (specialist unavailable, as opposed to [BrokenAsrModel]'s "loads fine but
 * fails to start") case with the same small type.
 */
class FakeRegisteredSpeechModel(
    id: String,
    languages: Set<Language>,
    priority: Int = 0,
    supportsCodeSwitching: Boolean = false,
    private val handleSupplier: suspend () -> SpeechModelHandle,
) : RegisteredSpeechModel {
    override val info = SpeechModelInfo(
        id = id,
        displayName = id,
        capabilities = SpeechModelCapabilities(
            languages = languages,
            supportsStreaming = true,
            supportsFileTranscription = true,
            supportsLanguageAutoDetection = languages.isEmpty(),
            supportsCodeSwitching = supportsCodeSwitching,
            engine = AsrEngineType.OTHER,
        ),
        priority = priority,
    )

    var loadCount = 0
        private set

    override suspend fun handle(): SpeechModelHandle {
        loadCount++
        return handleSupplier()
    }
}

/** Working specialist/fallback — the common case in most tests. */
fun fakeModel(id: String, languages: Set<Language>, priority: Int = 0, codeSwitching: Boolean = false): FakeRegisteredSpeechModel {
    val handle = FakeAsrModel(id)
    return FakeRegisteredSpeechModel(id, languages, priority, codeSwitching) { handle }
}

/** A registered model whose handle() itself throws — "specialist unavailable" (e.g. no model file on disk), distinct from one that loads but fails to start. */
fun unavailableModel(id: String, languages: Set<Language>): FakeRegisteredSpeechModel =
    FakeRegisteredSpeechModel(id, languages) { throw IllegalStateException("$id is not installed") }

/** A registered model that loads fine but whose session fails to start. */
fun brokenModel(id: String, languages: Set<Language>): FakeRegisteredSpeechModel {
    val handle = BrokenAsrModel(id)
    return FakeRegisteredSpeechModel(id, languages) { handle }
}

/** Scripted LID: returns the next result in [script] on each call, in order; throws if called more times than scripted (a test asserting too few/many evaluations happened is a real signal, not a fixture bug to paper over). */
class ScriptedLanguageIdentifier(private val script: List<LanguageIdResult>) : LanguageIdentifier {
    private var index = 0
    val callCount: Int get() = index

    override suspend fun identify(audio: AudioChunk): LanguageIdResult {
        check(index < script.size) { "ScriptedLanguageIdentifier called more times (${index + 1}) than scripted (${script.size})" }
        return script[index++]
    }
}

fun lid(language: Language, confidence: Float, isMixed: Boolean = false) = LanguageIdResult(language = language, confidence = confidence, isMixed = isMixed)
