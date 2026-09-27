# Mobile_mem0 dependency

`:commercial-memory` (this repo's private context-ranking layer, see its own
README) and `:core-chat` (via `api`) depend on the generic memory API that
also lives, published separately, as
[rmant7/Mobile_mem0](https://github.com/rmant7/Mobile_mem0).

`:core` itself does **not** depend on Mobile_mem0, `:commercial-memory`, or
`:core-chat` — see "The :core / :core-chat split" below. That is a deliberate
architectural boundary, not an oversight: keep it that way rather than
re-adding a memory dependency to `:core` to make some future addition
slightly more convenient.

## Current state: the published artifact, via JitPack

`Mobile_mem0` was tagged `v0.1.0` after its own `MemoryProviderContractTest`
suite was added and `consolidate()` was hardened against a throwing
extractor, duplicate/WORKING-scoped extractor output, and concurrent
`consolidate()` calls for the same conversation — see that repo's own commit
history for the detail. `:core-chat` and `:commercial-memory` depend on it
directly (now pinned to `v0.3.0-alpha3`, both together — see "must depend on
the exact same artifact/version" below):

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.rmant7:Mobile_mem0:v0.3.0-alpha3") // :commercial-memory
    api("com.github.rmant7:Mobile_mem0:v0.3.0-alpha3")            // :core-chat
}
```

`:app` also declares the same `jitpack.io` repository even though it does
not depend on Mobile_mem0 directly — it depends on `:core-chat` and
`:commercial-memory` (both `implementation`), and Gradle resolves every
module's own `compileClasspath` against *that module's own*
`repositories {}` block, not whatever a `project()` dependency's build
script declared. Skipping this fails with
`Could not find com.github.rmant7:Mobile_mem0:...` at compile time — not
a hypothetical, this is exactly what the first CI run against this branch's
switch to the artifact hit (back when `:core`, not `:core-chat`, held the
dependency; the mechanism is identical after the split). `:core` and
`:openai` need no such repository any more — neither one, transitively or
directly, touches Mobile_mem0.

**`:core-chat` and `:commercial-memory` must depend on the exact same
artifact/version, not just similar ones.** `NodeExecutors` (in `:core-chat`)
passes its own `MemoryProvider` instance into `MemoryExperimentRunner` (in
`:commercial-memory`, constructed in `AppContainer`) — if the two modules
resolved even slightly different versions of Mobile_mem0, the JVM would
treat their otherwise-identical `MemoryProvider`/`MemoryItem` classes as two
distinct, incompatible types, and `:app` would fail to compile. This is not
a hypothetical: it is exactly why `:memory` (the in-tree stand-in this
branch used before the tag existed) could not be swapped out in only one of
the two modules.

## The `:core` / `:core-chat` split

Originally `:core` itself held `NodeExecutors` (the pipeline wiring that
actually queries memory/knowledge) and depended on Mobile_mem0 and
`:commercial-memory` via `api(...)`, so *anything* depending on `:core` —
including a hypothetical consumer that only wants cloud-model routing, the
error taxonomy, the model catalog, or the SINGLE/COMPARE execution engine,
none of which touch memory — pulled in the whole memory/RAG dependency
graph too, with no way to opt out.

`NodeExecutors` and the `LlmMemoryExtractor` it uses moved to a new module,
`:core-chat`, which depends on `:core` (not the other way around) plus
Mobile_mem0 and `:commercial-memory`. To make that direction possible,
`Orchestrator` (which stays in `:core`, in `core/engine/Orchestrator.kt`)
no longer takes a `NodeExecutors` in its constructor — only a
`PipelineEngine`, which is memory-agnostic. A caller that wants memory
builds its own `NodeExecutors` (from `:core-chat`) and passes
`Orchestrator(router, PipelineEngine(executors.build()))`, same as before;
a caller with no use for memory (translation, or a future consumer wanting
only cloud models) never needs `:core-chat` on its classpath at all.

`:app` is the only current consumer of `:core-chat`, and now declares it
(and `:commercial-memory`) as explicit `implementation` dependencies in its
own `build.gradle.kts`, since it no longer gets them transitively through
`:core`'s old `api(...)` declarations.

## Why `:memory` (in-tree) was the dependency before this

Before `v0.1.0` existed, `:commercial-memory` depended on this repo's own
`:memory` module, not a copy: `:memory` is the original module Mobile_mem0's
public API was itself extracted *from* (see Mobile_mem0's own README, which
says exactly that). Pointing at `com.github.rmant7:Mobile_mem0:<tag>` before
any tag existed would have either failed to resolve or silently pinned to
`main-SNAPSHOT` — the "permanent SNAPSHOT dependency for production" this
project's own conventions rule out.

`:memory` itself is untouched and still buildable/testable on its own
(`./gradlew :memory:test` still passes) — it is simply no longer referenced
by `:core` or `:commercial-memory` on this branch. Whether to remove it from
this repo entirely is a separate decision, deliberately not made here.

## History: `mem0` and `main`

This switch was made on a branch named `mem0` (formerly
`commercial-memory-layer-v1`), developed in parallel with `main` while
`main` still shipped `:app`/`:core` against `:memory` in-tree. `mem0` also
built under a different `applicationId` (`ai.localstudio.app.mem0`) so it
could be installed on the same device as a `main` build at the same time,
without one overwriting the other.

`mem0` has since been fast-forward-merged into `main` — there is no longer
a separate branch or a separate `applicationId` to distinguish between:
`main` is this state, full stop, and `:app`'s `applicationId` is back to
plain `ai.localstudio.app`.
