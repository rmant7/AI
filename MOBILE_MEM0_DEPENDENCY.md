# Mobile_mem0 dependency

`:commercial-memory` (this repo's private context-ranking layer, see its own
README) and `:core` (via `api`) depend on the generic memory API that also
lives, published separately, as
[rmant7/Mobile_mem0](https://github.com/rmant7/Mobile_mem0).

## Current state (this branch): the published artifact, via JitPack

`Mobile_mem0` was tagged `v0.1.0` after its own `MemoryProviderContractTest`
suite was added and `consolidate()` was hardened against a throwing
extractor, duplicate/WORKING-scoped extractor output, and concurrent
`consolidate()` calls for the same conversation — see that repo's own commit
history for the detail. Both `:core` and `:commercial-memory` now depend on:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.rmant7:Mobile_mem0:v0.1.0") // :commercial-memory
    api("com.github.rmant7:Mobile_mem0:v0.1.0")             // :core
}
```

**Both must depend on the exact same artifact/version, not just similar
ones.** `NodeExecutors` (in `:core`) passes its own `MemoryProvider` instance
into `MemoryExperimentRunner` (in `:commercial-memory`, constructed in
`AppContainer`) — if the two modules resolved even slightly different
versions of Mobile_mem0, the JVM would treat their otherwise-identical
`MemoryProvider`/`MemoryItem` classes as two distinct, incompatible types,
and `:app` would fail to compile. This is not a hypothetical: it is exactly
why `:memory` (the in-tree stand-in this branch used before the tag existed)
could not be swapped out in only one of the two modules.

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

## What this means for `main`

This switch was made on `commercial-memory-layer-v1` only. `main` (and
`:app`/`:core` as shipped) still depend on `:memory` in-tree — moving the
rest of the app onto the published artifact too is the same "bigger
decision" this document flagged before the tag existed, and still isn't
made here. Do not assume this branch's `build.gradle.kts` state reflects
what should ship from `main`.
