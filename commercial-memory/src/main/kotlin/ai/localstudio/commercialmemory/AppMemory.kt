package ai.localstudio.commercialmemory

import ai.localstudio.memory.MemoryItem
import ai.localstudio.memory.MemoryProvider
import ai.localstudio.memory.MemoryQuery
import ai.localstudio.memory.MemoryScope

/**
 * The one place the rest of this application talks to the generic memory
 * layer (Mobile_mem0's [MemoryProvider]). Everything above this line —
 * [MemoryExperimentRunner], [ContextRanker], the pipeline glue in `:core` —
 * depends on [AppMemory], never on [MemoryProvider] directly, so a change to
 * Mobile_mem0's internals (or swapping which implementation of
 * [MemoryProvider] is wired up) touches this one class and nothing else.
 *
 * Deliberately thin: this is a facade, not a second copy of Mobile_mem0's
 * API. It renames `search` to `candidates` because that is what its caller
 * (context ranking) actually wants from it — a pool of candidates to score,
 * not a final answer — but it does no ranking, budgeting, or selection of
 * its own. That is [ContextRanker] and [ContextSelector]'s job, on purpose:
 * see this module's README for the boundary this split exists to enforce.
 */
class AppMemory(private val provider: MemoryProvider) {

    /** Returns the id of the stored memory — see [MemoryProvider.remember]. */
    suspend fun remember(
        text: String,
        scope: MemoryScope,
        metadata: Map<String, String> = emptyMap(),
    ): String = provider.remember(text, scope, metadata)

    /**
     * Raw retrieval candidates for [query] — generic lexical matches from
     * Mobile_mem0, not yet ranked or budgeted for a specific model or turn.
     * [limit] is deliberately generous by default (wider than what will
     * actually be injected into a prompt): the whole point of a separate
     * ranking/selection step afterward is to choose well from a larger pool,
     * not to have retrieval itself decide what is relevant.
     */
    suspend fun candidates(
        query: String,
        scopes: Set<MemoryScope> = setOf(MemoryScope.EPISODIC, MemoryScope.SEMANTIC),
        limit: Int = DEFAULT_CANDIDATE_LIMIT,
    ): List<MemoryItem> = provider.search(MemoryQuery(text = query, scopes = scopes, limit = limit))

    suspend fun forget(id: String) = provider.forget(id)

    /** Distils a finished conversation's working memory into durable memories. */
    suspend fun consolidate(conversationId: String): List<MemoryItem> = provider.consolidate(conversationId)

    private companion object {
        const val DEFAULT_CANDIDATE_LIMIT = 40
    }
}
