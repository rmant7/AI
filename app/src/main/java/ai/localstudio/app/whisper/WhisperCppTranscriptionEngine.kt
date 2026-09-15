package ai.localstudio.app.whisper

import ai.localstudio.core.benchmark.TranscriptionEngine
import ai.localstudio.core.benchmark.TranscriptionEngineSession
import ai.localstudio.core.capability.Capability
import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript
import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import ai.localstudio.core.runtime.SpeechModelHandle
import ai.localstudio.whisper.WhisperBridge

/**
 * The app's "current backend" (see docs/16-stt-benchmark.md) as a benchmark
 * [TranscriptionEngine] — a thin adapter over [WhisperCppRuntime], not a
 * new engine implementation: benchmarking must measure the exact code path
 * every other screen already uses, not a specially-tuned variant of it.
 *
 * Loads its own dedicated [SpeechModelHandle], independent of
 * [ai.localstudio.app.AppContainer.whisperFileTranscriber]/
 * `whisperFallbackModel`/etc: sharing one of those would mean a benchmark
 * run's model_load_time reads near-zero whenever some other feature had
 * already warmed the same model, silently corrupting exactly the number
 * this exists to measure honestly. [release] frees this dedicated handle;
 * it never touches any of the app's other resident Whisper instances.
 */
class WhisperCppTranscriptionEngine(
    private val runtime: WhisperCppRuntime,
    private val whisperStore: WhisperStore,
    private val seed: WhisperModelSeed,
) : TranscriptionEngine {

    override val backendId: String = "whisper_cpp"
    override val displayName: String = "Whisper.cpp (current)"

    // Pinned in whisper/src/main/cpp/CMakeLists.txt's own GIT_TAG — whisper.cpp
    // exposes no version string through this app's JNI bridge to query at
    // runtime, so this constant has to be kept in sync by hand if that tag
    // is ever bumped. Recorded rather than "unknown" because it genuinely
    // is knowable, just not from inside the running app.
    override val backendVersion: String = "whisper.cpp v1.9.3"

    override val modelId: String = seed.id
    override val precision: String = precisionOf(seed)

    override suspend fun load(): TranscriptionEngineSession {
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
        val handle = runtime.load(descriptor, descriptor.bindings.first()) as SpeechModelHandle
        return Session(handle)
    }

    private class Session(private val handle: SpeechModelHandle) : TranscriptionEngineSession {
        // Not read back from the loaded handle (whisper.cpp's own JNI
        // surface doesn't expose it) — this is the same
        // WhisperBridge.defaultThreads() value WhisperCppRuntime itself
        // was constructed with app-wide, so it is accurate as long as
        // nothing constructs a differently-configured runtime instance —
        // true everywhere in this app today (one shared instance).
        override val threads: Int = WhisperBridge.defaultThreads()

        override suspend fun warmUp(sample: AudioRef) {
            handle.transcribe(sample, language = null)
        }

        // language = null (auto-detect) unless the benchmark run forced
        // one. Honest limitation, not fixed here: WhisperCppSpeechModel's
        // own transcribe() echoes back the *input* language argument on
        // Transcript.language, not whisper.cpp's own auto-detected result
        // — so a genuinely auto-detected language does not surface through
        // this path today, and BenchmarkRunMetrics.detectedLanguage will
        // read null for an unforced run rather than a fabricated guess.
        override suspend fun transcribe(audio: AudioRef): Transcript = handle.transcribe(audio, language = null)

        override fun release() = handle.close()
    }

    private companion object {
        fun precisionOf(seed: WhisperModelSeed): String = when {
            seed.modelUrl.contains("q5_0") -> "q5_0"
            seed.modelUrl.contains("q5_1") -> "q5_1"
            seed.modelUrl.contains("q8_0") -> "q8_0"
            seed.modelUrl.contains("q4_0") -> "q4_0"
            // Tiny/base/small ship fp16 unquantized — see WhisperModels'
            // own doc comment on why those specifically aren't quantized.
            else -> "fp16"
        }
    }
}
