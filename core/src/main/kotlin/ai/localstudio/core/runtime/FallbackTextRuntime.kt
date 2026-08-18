package ai.localstudio.core.runtime

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.registry.RuntimeKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One provider this chain can fall through to, tried in the order the list is built in. */
data class FallbackCandidate(
    val label: String,
    val runtime: ModelRuntime,
    val model: ModelDescriptor,
    val binding: RuntimeBinding,
)

/**
 * Not a real inference backend — an ordered list of other runtimes, tried in
 * turn within a single request. "Local first, cloud as a safety net" is a
 * policy the caller expresses by ordering [candidates]; this class just
 * tries each in order and moves on when one throws or produces nothing.
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

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            val handle = try {
                candidate.runtime.load(candidate.model, candidate.binding) as? TextModelHandle
                    ?: throw ModelLoadException("${candidate.label} did not load as a text model")
            } catch (e: Exception) {
                failures += "${candidate.label}: ${e.message ?: e.toString()}"
                continue
            }

            active = handle
            try {
                // Buffered, not emitted live: a candidate that produces
                // several tokens and then throws must not leave a truncated
                // partial answer on screen with no way to fall back cleanly
                // — the whole point of this class is that a failure is
                // invisible to whatever is waiting on the Flow.
                val tokens = mutableListOf<String>()
                handle.generate(request).collect { tokens += it }
                if (tokens.isNotEmpty()) {
                    tokens.forEach { emit(it) }
                    return@flow
                }
                failures += "${candidate.label}: пустой ответ"
            } catch (e: Exception) {
                failures += "${candidate.label}: ${e.message ?: e.toString()}"
            } finally {
                handle.close()
                active = null
            }
        }
        throw ModelLoadException("Ни один источник не ответил:\n" + failures.joinToString("\n"))
    }

    override fun requestCancel() {
        active?.requestCancel()
    }

    override fun close() {
        active?.close()
    }
}
