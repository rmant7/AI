package ai.localstudio.core.benchmark

import ai.localstudio.core.model.AudioRef
import ai.localstudio.core.model.Transcript

/**
 * One transcription backend under benchmark — whisper.cpp today, CTranslate2
 * or another engine later (see docs/16-stt-benchmark.md). [BenchmarkRunner]
 * knows only this interface: adding a new engine means implementing it,
 * never touching the runner, the same seam [ai.localstudio.core.speech.RegisteredSpeechModel]
 * already establishes for the language router. Deliberately a *separate*
 * interface from [ai.localstudio.core.runtime.SpeechModelHandle]/
 * [ai.localstudio.core.speech.RegisteredSpeechModel] rather than reusing
 * them directly: a benchmark needs identity metadata (backend name,
 * version, precision) neither of those carries, and needs an explicit,
 * separately-timed [TranscriptionEngineSession.warmUp] step that has no
 * place in the router's own contract.
 */
interface TranscriptionEngine {
    /** Stable identifier for the backend itself, e.g. "whisper_cpp" — not the model. */
    val backendId: String

    /** Human-readable label for summary tables/reports, e.g. "whisper.cpp (current)". */
    val displayName: String

    /** Whatever version string is actually available — a build/commit id, a library version, or "unknown" if the engine cannot report one. Never fabricated. */
    val backendVersion: String

    /** The specific model this engine instance runs, e.g. "large-v3". */
    val modelId: String

    /** Quantization/precision actually in effect, e.g. "q5_0", "fp16" — "unknown" if not determinable, never guessed. */
    val precision: String

    /**
     * Loads the model. [BenchmarkRunner] measures this call's own wall-clock
     * duration as `model_load_time` — this method must not do anything but
     * the load itself (no warm-up here; see [TranscriptionEngineSession.warmUp]).
     */
    suspend fun load(): TranscriptionEngineSession
}

/** One loaded, ready-to-use instance of a [TranscriptionEngine]. */
interface TranscriptionEngineSession {
    /** Thread count actually used for inference, if the engine exposes/controls one — null if not applicable. */
    val threads: Int?

    /**
     * One throwaway inference against [sample] to prime whatever the engine
     * needs primed (native buffers, JIT, thread pools, caches) before real
     * measurements start — cold start and steady-state speed must not be
     * mixed into the same number. [BenchmarkRunner] measures this call's own
     * duration as `warm_inference_time`. An engine that genuinely cannot be
     * warmed up correctly should throw rather than silently no-op, so the
     * gap is recorded rather than hidden — see [BenchmarkRunner]'s own doc
     * comment.
     */
    suspend fun warmUp(sample: AudioRef)

    /**
     * Transcribes one file. Deliberately does no timing of its own — the
     * runner measures wall-clock around this call uniformly across every
     * engine, so no implementation can under- or over-report by choosing
     * what counts as "processing."
     */
    suspend fun transcribe(audio: AudioRef): Transcript

    /** Frees whatever [load] allocated. Safe to call once the session is no longer needed. */
    fun release()
}
