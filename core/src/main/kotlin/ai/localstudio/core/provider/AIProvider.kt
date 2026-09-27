package ai.localstudio.core.provider

import ai.localstudio.core.runtime.ModelRuntime

/**
 * A cloud or local AI backend: an id, a way to find out what models it
 * currently offers, and the [ModelRuntime] that actually runs them.
 *
 * Deliberately thin, and deliberately reuses [ModelRuntime] rather than
 * introducing a competing request/response shape — every real execution
 * path in this app (local llama.cpp, an OpenAI-compatible endpoint, AICore)
 * already goes through [ModelRuntime], and the [ai.localstudio.core.runtime.FallbackTextRuntime]/
 * [ai.localstudio.core.runtime.RuntimeManager] machinery built on top of it
 * (candidate ordering, residency, cooldowns) is what sessions 6-7 of
 * docs/17-ai-core-stage1.md move out of `AppContainer` and onto this
 * interface — inventing a second, parallel execution abstraction here would
 * just be something that refactor has to unwind.
 *
 * [discoverModels] is what's actually new: [ModelRuntime] has no notion of
 * "what models does this backend currently offer" — that's answered today
 * by a hardcoded list in application code (`CloudProviders.freeModels`).
 * A provider with nothing to discover (the local runtime, AICore — neither
 * has an HTTP `/models` endpoint) returns an empty list; its models come
 * entirely from this app's own catalog, same as before this interface
 * existed.
 */
interface AIProvider {
    val id: String
    val runtime: ModelRuntime
    suspend fun discoverModels(): List<DiscoveredModel>
}
