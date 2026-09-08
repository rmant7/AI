package ai.localstudio.core.runtime

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One provider this chain can fall through to, tried in the order the list is built in. */
data class FallbackCandidate(
    val label: String,
    val runtime: ModelRuntime,
    val model: ModelDescriptor,
    val binding: RuntimeBinding,
    /**
     * Notified with the raw exception whenever this candidate fails to
     * answer — load or generate, same as what feeds [failures] below. Lets
     * the caller apply its own cooldown policy for a specific kind of
     * failure (an HTTP 503 from a specific cloud model, say) without this
     * class needing to know what that failure type even is.
     */
    val onFailure: ((Throwable) -> Unit)? = null,
)

/**
 * Not a real inference backend — an ordered list of other runtimes, tried in
 * turn within a single request. "Local first, cloud as a safety net" is a
 * policy the caller expresses by ordering [candidates]; this class just
 * tries each in order and moves on when one throws or produces nothing.
 *
 * Every answer carries a trailing "Ответ от: <label>" line naming whichever
 * candidate actually produced it — with no attribution at all, "местная
 * модель" and "Gemini's third free-tier fallback model" are indistinguishable
 * from the outside, which is exactly what made a wrong-looking answer
 * impossible to diagnose without the underlying request/response logs.
 *
 * This only rescues Kotlin-level failures — a thrown exception, a timeout, an
 * empty response. A genuine native crash (a segfault in llama.cpp, say) kills
 * the process outright and nothing runs afterward, in Kotlin or otherwise;
 * what this buys is a local model erroring or hanging no longer being a dead
 * end for the turn when a cloud provider is configured as a fallback.
 */
class FallbackTextRuntime(private val candidates: List<FallbackCandidate>) : ModelRuntime {

    init {
        require(candidates.isNotEmpty()) { "FallbackTextRuntime needs at least one candidate" }
    }

    override val kind: RuntimeKind = RuntimeKind.FALLBACK_CHAIN

    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean = true

    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel =
        FallbackTextModel(candidates)
}

private class FallbackTextModel(private val candidates: List<FallbackCandidate>) : TextModelHandle {
    override val modelId: String = "fallback-chain"
    override val ramBytes: Long = 0

    @Volatile
    private var active: TextModelHandle? = null

    /**
     * Candidates loaded so far, by index, kept across turns.
     *
     * This used to load and then close every candidate inside a single
     * generate() call, which meant a local model was read off disk again for
     * every message the moment any second provider was enabled — six to
     * twenty seconds per turn for a 4B GGUF on a real device. Worse, a fresh
     * llama.cpp context starts with an empty KV cache, so the prefix reuse
     * in llama_jni.cpp had nothing to match against and the entire prompt
     * was re-prefilled every turn too. The single-candidate path never had
     * this problem: it goes through RuntimeManager, which keeps the model
     * resident. This map is the equivalent for the chain, and
     * [close] — called by RuntimeManager when this chain itself is evicted —
     * is what eventually frees them.
     */
    private val loaded = mutableMapOf<Int, TextModelHandle>()

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        val failures = mutableListOf<String>()
        for ((index, candidate) in candidates.withIndex()) {
            val handle = try {
                loaded.getOrPut(index) {
                    candidate.runtime.load(candidate.model, candidate.binding) as? TextModelHandle
                        ?: throw ModelLoadException("${candidate.label} did not load as a text model")
                }
            } catch (e: CancellationException) {
                // A cancelled load (the user stopped generation, or the
                // overall request timed out) is not this candidate failing —
                // treating it as one used to make the chain silently move on
                // to the *next* candidate instead of actually stopping,
                // which from the outside looked exactly like Stop doing
                // nothing while the app kept querying a different provider.
                throw e
            } catch (e: Exception) {
                candidate.onFailure?.invoke(e)
                failures += "${candidate.label}: ${e.message ?: e.toString()}"
                continue
            }

            active = handle
            var emittedAny = false
            try {
                // Emitted live rather than buffered to the end. Buffering was
                // how a candidate that produced several tokens and then threw
                // could still be replaced silently by the next one — but it
                // also meant nothing at all reached the screen until the whole
                // answer was finished, which for a slow local model is minutes
                // of a blank bubble. That cost is paid on every turn; the
                // failure it guarded against is rare, and is still handled:
                // falling through to the next candidate stays possible right
                // up until the first token is emitted, and after that point a
                // failure is reported rather than silently papered over with a
                // second candidate's answer appended to the first one's.
                handle.generate(request).collect {
                    emittedAny = true
                    emit(it)
                }
                if (emittedAny) {
                    // Always attached, not just on fallback: with no
                    // attribution at all a plain answer just reads as "the
                    // model" with no way to tell which provider or which of
                    // several free-tier models on that provider actually
                    // produced it — and when a fallback earlier in the
                    // chain DID fail, that reason is exactly the signal
                    // needed to tell whether "local doesn't really work yet"
                    // is a real problem or a one-off, which silently
                    // discarding it once something else answers would lose.
                    emit(attributionFooter(candidate.label, failures))
                    return@flow
                }
                failures += "${candidate.label}: пустой ответ"
            } catch (e: CancellationException) {
                // Same reasoning as the load-side catch above: propagate
                // instead of recording it as this candidate's failure and
                // falling through to the next one.
                throw e
            } catch (e: Exception) {
                candidate.onFailure?.invoke(e)
                failures += "${candidate.label}: ${e.message ?: e.toString()}"
                // A handle that threw mid-generation is not trusted for the
                // next turn — dropped from the cache and closed here rather
                // than reused, unlike the success path.
                loaded.remove(index)?.let { broken -> runCatching { broken.close() } }
                if (emittedAny) {
                    // Part of this candidate's answer is already on screen.
                    // Falling through to the next candidate would append a
                    // second, unrelated answer to the first one's remains, so
                    // this is reported instead of silently recovered from.
                    throw e
                }
            } finally {
                active = null
            }
        }
        throw ModelLoadException("Ни один источник не ответил:\n" + failures.joinToString("\n"))
    }

    private fun attributionFooter(answeredBy: String, failures: List<String>) = buildString {
        append("\n\n---\n")
        if (failures.isNotEmpty()) {
            append("⚠ ")
            append(failures.joinToString("; "))
            append("\n")
        }
        append("Ответ от: ")
        append(answeredBy)
    }

    override fun requestCancel() {
        active?.requestCancel()
    }

    override fun close() {
        // Every candidate this chain ever loaded, not just whichever one
        // answered last: they are kept resident across turns now (see
        // [loaded]), so this is the only place they are released.
        loaded.values.forEach { runCatching { it.close() } }
        loaded.clear()
        active = null
    }
}
