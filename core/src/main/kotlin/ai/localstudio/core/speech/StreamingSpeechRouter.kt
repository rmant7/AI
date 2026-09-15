package ai.localstudio.core.speech

import ai.localstudio.core.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow

/**
 * One in-progress routed session, fed audio incrementally — the router's
 * own counterpart to
 * [ai.localstudio.core.runtime.StreamingSpeechSession], one layer up: where
 * that interface is one model's session, this is a session that may
 * dispatch to *several* models over its lifetime as the detected language
 * changes. `acceptAudio` is `suspend` here (unlike the lower interface's
 * non-suspend, buffer-only contract) because the router does real
 * asynchronous work — LID, model selection, a specialist's own
 * `acceptAudio` — but it must still return quickly: see
 * [DefaultStreamingRoutingSession]'s own doc comment for how audio capture
 * stays decoupled from that work through internal, bounded buffering rather
 * than this call itself blocking on it.
 */
interface StreamingRoutingSession {
    suspend fun acceptAudio(pcm: ShortArray)

    /** No more audio is coming: flushes whatever the active model has buffered and completes [segments]/[decisions]. */
    suspend fun finish()

    /** Stops immediately, discarding anything unflushed, and completes [segments]/[decisions]. */
    suspend fun cancel()

    /** Segments as they arrive from whichever model is currently active — see [TranscriptSegment.language]/[TranscriptSegment.modelId] for which one produced a given segment. */
    val segments: Flow<TranscriptSegment>

    /** Every routing decision as it's made — see [RoutingDecision]'s own doc comment for why this exists. */
    val decisions: Flow<RoutingDecision>
}

/**
 * Builds routed sessions. One instance is shared across a screen/feature;
 * [start] is what a live-mic button or a file-transcription pass actually
 * calls — see docs/15-speech-routing.md's "file transcription and
 * microphone share architecture" note for why this takes no argument
 * distinguishing the two: both push [ShortArray] chunks from an
 * [ai.localstudio.core.audio.AudioSource] into the same session shape.
 */
interface StreamingSpeechRouter {
    fun start(): StreamingRoutingSession
}
