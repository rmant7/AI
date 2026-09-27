package ai.localstudio.core.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One source in a COMPARE batch (docs/17-ai-core-stage1.md) — the same
 * pattern [ai.localstudio.app.ChatActivity]'s Compare mode and
 * [ai.localstudio.app.TranslationActivity]'s multi-source translation both
 * already run, independently of each other and of this. [run] is a plain
 * suspend function rather than a concrete [ai.localstudio.core.engine.Orchestrator]
 * reference — [runCompare] doesn't need to know it's calling
 * `orchestrator.handle(request)` specifically, only that it produces a [T];
 * a caller wraps whatever it actually needs to run (an orchestrator call, a
 * local runtime's own generate loop) in this lambda, on whatever dispatcher
 * that call needs.
 */
data class CompareCandidate<out T>(
    val label: String,
    /**
     * True for a source the caller always includes regardless of whether
     * it's actually usable — Gemini Nano on a device AICore hasn't
     * confirmed can run it, forced into every batch anyway (see
     * `AppContainer.CompareSource`'s own doc comment). Its failure becomes
     * [CompareOutcome.Hidden] — reported to [runCompare]'s caller (so a
     * result card built for it can be removed), never shown as an error the
     * user never asked to see in the first place.
     */
    val hideOnFailure: Boolean = false,
    val run: suspend () -> T,
)

/** What one [CompareCandidate] produced — see [runCompare]. */
sealed interface CompareOutcome<out T> {
    data class Success<T>(val value: T) : CompareOutcome<T>
    data class Failed(val error: Throwable) : CompareOutcome<Nothing>

    /** [CompareCandidate.hideOnFailure] fired — nothing to show for this candidate at all. */
    data object Hidden : CompareOutcome<Nothing>
}

/**
 * What [error] becomes for a candidate with [hideOnFailure] set the way it
 * is — null means "this must propagate instead of being reported as this
 * candidate's own failure." A pure function, kept separate from
 * [runCompare]'s actual coroutine dispatch so it's tested without relying on
 * exactly how structured concurrency treats a child re-throwing its own
 * [CancellationException] (a distinction easy to get subtly wrong and hard
 * to verify without real coroutine dispatch — this way that risk is
 * confined to one small, pure, thoroughly-tested function).
 *
 * A plain [CancellationException] (the user tapped Stop, or a parent scope
 * was cancelled) is not this candidate failing — it's being told to stop,
 * and must keep propagating or a Stop button reads as that one source
 * failing on its own while every other source keeps going.
 * [TimeoutCancellationException] is the one [CancellationException]
 * subtype this treats as an ordinary failure instead: it's a `withTimeout`
 * *this candidate's own [CompareCandidate.run] set up expiring*, not an
 * external stop request — same distinction
 * [ai.localstudio.app.TranslationActivity] already drew by hand before this
 * existed.
 */
internal fun classifyCompareFailure(error: Throwable, hideOnFailure: Boolean): CompareOutcome<Nothing>? = when {
    error is CancellationException && error !is TimeoutCancellationException -> null
    hideOnFailure -> CompareOutcome.Hidden
    else -> CompareOutcome.Failed(error)
}

/**
 * Runs every one of [candidates] concurrently, calling [onOutcome] with each
 * one's own index and result as soon as it's known — never waiting for the
 * slowest candidate before reporting any other one's outcome. Real device
 * report this avoids: a Compare batch showing nothing at all on screen
 * until its slowest source (often the local model) finally answered or
 * timed out, even though every cloud source had already come back.
 *
 * Structured concurrency, not a caller-supplied [kotlinx.coroutines.CoroutineScope]:
 * cancelling the coroutine this suspends in cancels every still-running
 * candidate along with it — a batch a caller abandons (the screen closing,
 * an overall deadline) doesn't keep running candidates in the background
 * with no one left to report to.
 */
suspend fun <T> runCompare(
    candidates: List<CompareCandidate<T>>,
    onOutcome: suspend (index: Int, outcome: CompareOutcome<T>) -> Unit,
) = coroutineScope {
    candidates.mapIndexed { index, candidate ->
        async {
            val outcome = try {
                CompareOutcome.Success(candidate.run())
            } catch (e: Throwable) {
                classifyCompareFailure(e, candidate.hideOnFailure) ?: throw e
            }
            onOutcome(index, outcome)
        }
    }.awaitAll()
    Unit
}
