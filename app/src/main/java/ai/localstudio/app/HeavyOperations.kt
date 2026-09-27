package ai.localstudio.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts user-facing operations currently running that hold (or are about
 * to take) real device memory — chat and translation generation, voice
 * transcription — so background work that would compete for the same RAM
 * (semantic-memory indexing: loading E5, embedding batches under the shared
 * nativeOpMutex) can stay out of the way until they finish. See
 * [AppContainer.embedderBlockedBy].
 *
 * A stopgap until every native model goes through RuntimeManager's own
 * resource state: increment and decrement always happen in [track]'s
 * try/finally, so a thrown or cancelled operation can never leave the
 * counter stuck above zero and block indexing forever.
 */
class HeavyOperations {
    private val active = AtomicInteger(0)

    val isActive: Boolean get() = active.get() > 0

    suspend fun <T> track(block: suspend () -> T): T {
        active.incrementAndGet()
        try {
            return block()
        } finally {
            active.decrementAndGet()
        }
    }
}
