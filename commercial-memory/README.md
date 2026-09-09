# commercial-memory

The **private, application-specific** layer above Mobile_mem0's generic
memory infrastructure. This is Stage 3 of the memory architecture: a
measurable baseline, not a finished "smart" algorithm — see "What this
deliberately is not yet" below before extending it.

## Boundary

**Mobile_mem0 stores and retrieves; this module decides what matters.**

Mobile_mem0 (via [`AppMemory`](src/main/kotlin/ai/localstudio/commercialmemory/AppMemory.kt))
answers: what memories exist, which ones lexically match a query, what scope
and metadata an item has.

This module answers: which of those memories are useful for *this* request,
how they should be combined, how much context budget to allocate, and (once
Stage 4 exists) what feedback should change future selection.

Do not add to Mobile_mem0:
- user-specific ranking formulas or weights;
- personality/behavior profiles;
- model-specific prompt templates;
- response-quality predictors;
- anything from this module's own source.

This module depends on Mobile_mem0's public API only (via the `:memory`
module today — see `MOBILE_MEM0_DEPENDENCY.md` at the repo root for why that
is a stand-in, not the final dependency) and never reaches into its
internals.

## Pipeline

```
AppMemory.candidates(query)
        │
        ▼
   buildCandidates()      — scores raw MemoryItems into ContextCandidates
        │
        ▼
   ContextRanker.rank()   — HeuristicContextRanker, or NoRanking for BASIC_MEMORY
        │
        ▼
  ContextSelector.select() — CommercialContextSelector packs into ContextBudget
        │
        ▼
     ContextSelection      — handed to the caller's own prompt builder
```

[`MemoryExperimentRunner`] is the single entry point that runs this whole
pipeline (or skips it, for `MEMORY_OFF`) for one of three
[`ExperimentMode`]s, and logs an [`ExperimentRecord`] of what happened via
[`ExperimentLogger`] every time. That logging is the actual point of Stage 3:
collecting real data on whether `COMMERCIAL_MEMORY` is worth its cost over
`BASIC_MEMORY`, which nothing here assumes the answer to yet.

## What this deliberately is not yet (Stage 4)

Everything below stays out of this module on purpose, until there is real
data to justify it:

- automatic weight learning, reinforcement learning, or online learning;
- optimization from user corrections or feedback;
- automatic ranking retraining;
- behavioral or cognitive profiling;
- a response-quality predictor trained on real data;
- automatic prompt or parameter optimization.

`RankingWeights` are fixed engineering defaults, not fit on any data.
`HeuristicContextRanker` is intentionally simple and replaceable — see
`ContextRanker`'s own doc comment — precisely so that a future, genuinely
learned ranker can be swapped in without this module's contract, or
Mobile_mem0, changing.

## Replaceability

`ContextRanker` and `ContextSelector` are interfaces specifically so
`HeuristicContextRanker` and `CommercialContextSelector` can be replaced by
stronger private implementations later without touching Mobile_mem0 or the
rest of the application. Adding a real learned ranker is meant to be "write
a new `ContextRanker`," not "redesign this module."
