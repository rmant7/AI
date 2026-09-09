# Mobile_mem0 dependency

`:commercial-memory` (this repo's private context-ranking layer, see its own
README) depends on the generic memory API that also lives, published
separately, as [rmant7/Mobile_mem0](https://github.com/rmant7/Mobile_mem0).

## Current state: `:memory` is the real dependency, not a copy

`:commercial-memory` depends on this repo's own `:memory` module today, not
on an external Mobile_mem0 artifact. That is not "copying Mobile_mem0 source
into AI" — `:memory` is not a copy of anything: it is the original module
Mobile_mem0's public API was itself extracted *from* (see Mobile_mem0's own
README, which says exactly that). `:core` and `:app` have depended on
`:memory` in production since before this integration existed, and this
branch does not touch that.

## Why not switch to the published artifact yet

Mobile_mem0's own `build.gradle.kts` already has `maven-publish` configured
specifically so JitPack can resolve any tagged commit with no further setup
— see that repo's README for the exact dependency coordinates. But nothing
is tagged yet: this integration was built against Mobile_mem0's
`public-memory-foundation-v1` branch, not a released version. Pointing this
repo's build at `com.github.rmant7:Mobile_mem0:<tag>` before that tag exists
would either fail to resolve or silently pin to whatever `main` happens to
be at build time (`main-SNAPSHOT`) — exactly the "permanent SNAPSHOT
dependency for production" this project's own conventions rule out.

## What changes once Mobile_mem0 is tagged

Once `public-memory-foundation-v1` (or its successor) is reviewed, merged to
Mobile_mem0's `main`, and tagged (e.g. `v0.1.1`):

1. Add to `:commercial-memory/build.gradle.kts` (and remove the
   `project(":memory")` dependency):

   ```kotlin
   repositories {
       maven { url = uri("https://jitpack.io") }
   }

   dependencies {
       implementation("com.github.rmant7:Mobile_mem0:v0.1.1") // use the real tag
   }
   ```

2. Decide, separately, whether `:core`/`:app`'s own existing `:memory`
   dependency should also switch to the published artifact, or whether
   `:memory` stays as this repo's in-tree implementation while
   `:commercial-memory` alone depends on the external one. That is a bigger
   decision than this integration branch — it changes what the rest of the
   app depends on, not just the new private layer — and deliberately isn't
   made here.

Do not fabricate a version number or repository URL ahead of an actual tag
existing — see the top-level task notes this integration was built from.
