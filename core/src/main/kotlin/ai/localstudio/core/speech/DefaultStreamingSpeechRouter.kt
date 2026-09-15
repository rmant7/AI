package ai.localstudio.core.speech

import ai.localstudio.core.model.TranscriptSegment
import ai.localstudio.core.runtime.StreamingSpeechSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The default [StreamingSpeechRouter]: a rolling-window
 * [LanguageIdentifier] plus hysteresis decides *when* to switch models; a
 * [SpeechModelRegistry] decides *what* to switch to; each individual
 * model's own [StreamingSpeechSession] still decides its own utterance/
 * partial-vs-final boundaries exactly as it already does today — this
 * class only ever decides which one is currently active.
 *
 * **Audio chunk sizes, two of them, on purpose** (see docs/15-speech-
 * routing.md, "do not switch models on every tiny chunk"): the
 * [ShortArray]s passed to [DefaultStreamingRoutingSession.acceptAudio] are
 * transport-sized (~0.5-1s, whatever [ai.localstudio.core.audio.AudioSource]
 * happens to produce); [lidWindowMs] is a much larger rolling window
 * (default 3s) assembled from several of those, and [lidStrideMs] (default
 * 1s) is how often that window is actually re-evaluated. LID confidence
 * alone still isn't enough to switch — see [RoutingPolicy] for the
 * consecutive-agreement hysteresis on top of it.
 *
 * **Threading**: [DefaultStreamingRoutingSession.acceptAudio] only enqueues
 * — LID, model loading/switching and forwarding into the active model's own
 * `acceptAudio` all happen on a single background coroutine per session, so
 * a slow model load never blocks audio capture (section 21's requirement).
 * The inbox is bounded and non-suspending: a caller that outpaces
 * processing drops the newest chunk rather than blocking, and
 * [DefaultStreamingRoutingSession.droppedChunkCount] makes that visible
 * instead of silent.
 */
class DefaultStreamingSpeechRouter(
    private val registry: SpeechModelRegistry,
    private val languageIdentifier: LanguageIdentifier,
    private val policy: RoutingPolicy,
    private val scope: CoroutineScope,
    private val sampleRate: Int = 16_000,
    private val lidWindowMs: Int = 3_000,
    private val lidStrideMs: Int = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : StreamingSpeechRouter {

    override fun start(): StreamingRoutingSession = DefaultStreamingRoutingSession(
        registry = registry,
        languageIdentifier = languageIdentifier,
        policy = policy,
        scope = scope,
        sampleRate = sampleRate,
        lidWindowSamples = lidWindowMs * sampleRate / 1000,
        lidStrideSamples = lidStrideMs * sampleRate / 1000,
        clock = clock,
    )
}

private const val INBOX_CAPACITY = 64
private const val PENDING_PREBUFFER_CAP = 32

class DefaultStreamingRoutingSession(
    private val registry: SpeechModelRegistry,
    private val languageIdentifier: LanguageIdentifier,
    private val policy: RoutingPolicy,
    private val scope: CoroutineScope,
    private val sampleRate: Int,
    private val lidWindowSamples: Int,
    private val lidStrideSamples: Int,
    private val clock: () -> Long,
) : StreamingRoutingSession {

    private val inbox = Channel<ShortArray>(capacity = INBOX_CAPACITY)
    private val segmentsChannel = Channel<TranscriptSegment>(Channel.UNLIMITED)
    private val decisionsChannel = Channel<RoutingDecision>(Channel.UNLIMITED)
    override val segments: Flow<TranscriptSegment> = segmentsChannel.receiveAsFlow()
    override val decisions: Flow<RoutingDecision> = decisionsChannel.receiveAsFlow()

    /** Chunks dropped because [inbox] was full — see this class's own doc comment on bounded, non-blocking capture. */
    var droppedChunkCount: Int = 0
        private set

    /** Set when a specialist *and* the configured fallback both fail — see [selectModelFor]. Mirrors [ai.localstudio.app.whisper.WhisperCppMicSession.lastError]'s own established convention: there is no other way for a caller to tell "the session died" from "finish()/cancel() ended it normally" once [segments] has completed. */
    var lastError: Throwable? = null
        private set

    private val lidWindow = ShortArray(lidWindowSamples)
    private var lidWindowFill = 0
    private var samplesSinceLid = 0

    private var currentLanguage: Language? = null
    private var currentModel: RegisteredSpeechModel? = null
    private var currentModelSession: StreamingSpeechSession? = null

    /**
     * Every segment-collector coroutine ever started this session, not just
     * the latest — a mid-session switch calls the outgoing model's
     * `finish()` but its flush completes asynchronously on its own
     * coroutine (see [ai.localstudio.app.whisper.WhisperCppMicSession] /
     * [ai.localstudio.app.vosk.VoskSpeechRecognizer]'s own finish()
     * semantics), so cancelling its collector immediately would drop the
     * last few words of the outgoing utterance. Instead every collector
     * keeps running until its own model's `segments` flow completes
     * naturally, and this list is what lets [finish] join all of them
     * (not just the current one) and [cancel] actually stop all of them.
     */
    private val collectorJobs = mutableListOf<Job>()

    private var pendingLanguage: Language? = null
    private var pendingStableWindows = 0

    /** Audio that arrived before any model was selected yet — flushed into the first model chosen. */
    private val preBuffer = ArrayDeque<ShortArray>()

    private val processorJob = scope.launch { processInbox() }

    override suspend fun acceptAudio(pcm: ShortArray) {
        if (inbox.trySend(pcm).isFailure) droppedChunkCount++
    }

    override suspend fun finish() {
        inbox.close()
        processorJob.join()
        currentModelSession?.finish()
        collectorJobs.forEach { it.join() }
        segmentsChannel.close()
        decisionsChannel.close()
    }

    override suspend fun cancel() {
        inbox.close()
        processorJob.cancel()
        currentModelSession?.cancel()
        collectorJobs.forEach { it.cancel() }
        segmentsChannel.close()
        decisionsChannel.close()
    }

    private suspend fun processInbox() {
        for (chunk in inbox) {
            appendToLidWindow(chunk)
            samplesSinceLid += chunk.size

            if (samplesSinceLid >= lidStrideSamples && lidWindowFill >= lidWindowSamples) {
                samplesSinceLid = 0
                evaluateLanguage()
            }

            val session = currentModelSession
            if (session == null) {
                preBuffer += chunk
                if (preBuffer.size > PENDING_PREBUFFER_CAP) {
                    preBuffer.removeFirst()
                    droppedChunkCount++
                }
            } else {
                session.acceptAudio(chunk)
            }
        }
    }

    private fun appendToLidWindow(chunk: ShortArray) {
        // Simple fixed-size ring: shift left by chunk.size (dropping the
        // oldest samples), append at the end. Windows are only a few
        // seconds of 16kHz mono audio (tens of KB) and re-evaluated at
        // most once a second, so an O(window) shift here is cheap relative
        // to LID inference itself.
        val n = chunk.size
        if (n >= lidWindow.size) {
            chunk.copyInto(lidWindow, 0, n - lidWindow.size, n)
            lidWindowFill = lidWindow.size
        } else {
            val keep = lidWindow.size - n
            lidWindow.copyInto(lidWindow, 0, n, lidWindow.size)
            chunk.copyInto(lidWindow, keep)
            lidWindowFill = (lidWindowFill + n).coerceAtMost(lidWindow.size)
        }
    }

    private suspend fun evaluateLanguage() {
        val windowCopy = if (lidWindowFill == lidWindow.size) lidWindow.copyOf() else lidWindow.copyOfRange(lidWindow.size - lidWindowFill, lidWindow.size)
        val result = languageIdentifier.identify(AudioChunk(pcm = windowCopy, sampleRate = sampleRate, startMs = clock()))
        applyDecision(result)
    }

    /**
     * The hysteresis state machine — see [RoutingPolicy]'s own doc comment
     * for the exact numbers this reads. Every evaluated window records a
     * [RoutingDecision], not only ones that switch — see
     * [RoutingDecision]'s own doc comment for why "stayed" is as important
     * to log as "switched" during the experimental phase.
     */
    private suspend fun applyDecision(result: LanguageIdResult) {
        val routedLanguage = if (result.isMixed) Language.UNKNOWN else result.language
        // Captured before selectModelFor() can mutate currentLanguage/currentModel below —
        // otherwise a switch would report the same (new) value as both previous and selected.
        val previousLanguage = currentLanguage
        val previousModelId = currentModel?.info?.id
        val reason: String

        when {
            !policy.allowModelSwitching && currentModel != null -> {
                reason = "model switching disabled by policy; staying on ${currentModel?.info?.id}"
                pendingLanguage = null
                pendingStableWindows = 0
            }

            currentLanguage == null -> {
                // First-ever decision for this session: a lower bar
                // (minLanguageConfidence) applies, not the higher
                // switchConfidence a *change* needs — there is nothing yet
                // to have hysteresis against.
                if (!result.isMixed && result.confidence >= policy.minLanguageConfidence) {
                    reason = selectModelFor(routedLanguage, "initial pick, confidence ${result.confidence}")
                } else {
                    reason = selectModelFor(Language.UNKNOWN, "low-confidence or mixed initial result, falling back")
                }
                pendingLanguage = null
                pendingStableWindows = 0
            }

            routedLanguage == currentLanguage -> {
                reason = "confirmed $currentLanguage (${result.confidence})"
                pendingLanguage = null
                pendingStableWindows = 0
            }

            result.confidence < policy.switchConfidence -> {
                reason = "candidate $routedLanguage below switchConfidence (${result.confidence}); staying on $currentLanguage"
                pendingLanguage = null
                pendingStableWindows = 0
            }

            else -> {
                if (pendingLanguage == routedLanguage) {
                    pendingStableWindows++
                } else {
                    pendingLanguage = routedLanguage
                    pendingStableWindows = 1
                }

                reason = if (pendingStableWindows >= policy.minStableWindows) {
                    pendingLanguage = null
                    pendingStableWindows = 0
                    selectModelFor(routedLanguage, "stable language switch (${result.confidence})")
                } else {
                    "candidate $routedLanguage window $pendingStableWindows/${policy.minStableWindows}; staying on $currentLanguage"
                }
            }
        }

        decisionsChannel.trySend(
            RoutingDecision(
                timestampMs = clock(),
                lidResult = result,
                previousLanguage = previousLanguage,
                selectedLanguage = currentLanguage ?: routedLanguage,
                previousModelId = previousModelId,
                selectedModelId = currentModel?.info?.id,
                reason = reason,
            ),
        )
    }

    /**
     * Finds the best candidate for [language] via the registry and switches
     * to it, falling back to [RoutingPolicy.fallbackModelId] on failure —
     * see this method's own fallback branch. Returns a short reason string
     * for the [RoutingDecision] this call is part of.
     */
    private suspend fun selectModelFor(language: Language, why: String): String {
        val candidates = registry.findCandidates(language)
        val fallback = policy.fallbackModelId?.let { registry.get(it) }
        val ordered = if (fallback != null && candidates.none { it.info.id == fallback.info.id }) {
            candidates + fallback
        } else {
            candidates
        }

        for (candidate in ordered) {
            val started = tryStart(candidate, language)
            if (started) {
                lastError = null
                return "$why -> ${candidate.info.id}"
            }
        }

        lastError = IllegalStateException("No ASR model available for $language (tried ${ordered.map { it.info.id }})")
        return "$why -> no candidate available"
    }

    private suspend fun tryStart(candidate: RegisteredSpeechModel, language: Language): Boolean {
        val languageCode = when (language) {
            Language.RU -> "ru"
            Language.EN -> "en"
            Language.HE -> "he"
            Language.UNKNOWN -> null
        }

        val newSession = try {
            val handle = candidate.handle()
            handle.startStreaming(languageCode)
        } catch (e: Exception) {
            lastError = e
            return false
        }

        // Flush whatever the previous model had buffered before switching
        // away from it — a graceful finish, not a cancel, so the tail of
        // its last utterance still reaches segments. Its collector (below,
        // from the *previous* call to tryStart) is deliberately left
        // running, not cancelled — see collectorJobs' own doc comment.
        currentModelSession?.finish()

        currentModel = candidate
        currentLanguage = language
        currentModelSession = newSession

        collectorJobs += scope.launch {
            newSession.segments.collect { segment ->
                segmentsChannel.trySend(segment.copy(language = language, modelId = candidate.info.id))
            }
        }

        if (preBuffer.isNotEmpty()) {
            val buffered = preBuffer.toList()
            preBuffer.clear()
            buffered.forEach { newSession.acceptAudio(it) }
        }

        return true
    }
}
