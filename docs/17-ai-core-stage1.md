# AI Core — Stage 1

Turn this repository's AI logic into an internal, self-contained AI Core that
Local AI Studio runs on, and that IntelliVerse (`rmant7/IntelliverseHybrid`)
can later consume as a second client. Stage 1 is a refactor of working code,
not a public SDK: no Maven publishing, no binary-compatibility promises, no
IntelliVerse code or dependency anywhere in this build.

Estimate: **13–14 Sonnet sessions (≈5 h each)**; native Gemini generation is
Stage 2. Every session ends green on CI with the app still fully working.

## Architecture

```
                         AI Core
            ┌───────────────┴───────────────┐
     Execution Engine                  Model Catalog
     ┌──────┴──────┐              ┌────────┼─────────┐
   SINGLE       COMPARE        repo    remote    provider
     └──────┬──────┘          catalog  catalog  discovery
     Candidate Router  ←── ResourceBudget.canRun(candidate)
     ┌──────┼────────┐
   Cloud  Local   AICore
     │
 Groq · Gemini (OpenAI-compatible) · GigaChat · Mistral · xAI · OpenRouter · Custom
```

### Execution patterns

Both are core functions, not app code — Local AI's Chat/Translation compare
mode and IntelliVerse's `BaseResultViewModel` are the same pattern.

- **SINGLE** — pick one candidate, fall back along the policy on failure.
- **COMPARE** — run N candidates in parallel, stream each result
  independently; a candidate may be marked hide-on-failure (Gemini Nano).

### Model catalog — normalization, not just discovery

```
provider API → raw metadata → ModelCatalog → normalized ModelDescriptor
                                   + repo/remote metadata → eligible candidates
```

Providers report very different amounts: Gemini `models.list` gives token
limits and supported methods, OpenRouter gives modalities and pricing, Groq a
context window, Mistral/xAI little beyond an id. Capabilities not reported by
the provider come from our catalog.

Statuses:

| Status | Meaning |
|---|---|
| DISCOVERED | The provider reports it; not in our catalog. Manual selection only. |
| VERIFIED | In our versioned catalog and allowed for Auto. |
| EXPERIMENTAL | In our catalog, not allowed for Auto (Gemini preview/experimental by default). |
| DEPRECATED | Must not be used; wins even if the provider still lists the model. |
| UNAVAILABLE | Known, but currently not offered / not reachable. |

Merge sources: repo catalog (bundled, offline fallback) + remote catalog
(same JSON format, updatable without an APK) + provider discovery.
Precedence: remote over repo for metadata and status. Discovery may only add
DISCOVERED entries or mark known ones UNAVAILABLE; it never promotes to
VERIFIED. The remote catalog may describe models of existing providers only —
it can never add a provider, a base URL or credentials.

### Errors, retry, fallback

One `AIError` taxonomy (AUTHENTICATION, RATE_LIMIT, QUOTA, NETWORK, TIMEOUT,
UNAVAILABLE, NOT_FOUND, INVALID_REQUEST, CONTENT_ERROR, RUNTIME_ERROR,
OUT_OF_MEMORY, UNSUPPORTED, UNKNOWN), keeping the provider's original status
and body. One policy decides: 429 → next key → retry; 503 → model cooldown →
next candidate; auth → disable that key; not found → next candidate;
OOM → budget/eviction. Key pooling (`core/keys/ApiKeyPool.kt`) is already
Android-free and is reused as is.

### ResourceBudget (limited)

One RAM ledger across the local LLM, E5, Whisper/Vosk and the AICore reserve,
and `canRun(candidate, currentResources)` for the router — enough to stop
"LLM + E5 + Whisper + AICore at once". Full lifecycle coordination is Stage 2.

### Metrics

`AIExecutionMetrics` (provider, model, capability, start, duration, TTFT,
input/output tokens, retries, fallbacks, error; for local also load time,
peak RAM, tok/s) delivered through a listener; log lines are derived from it.

## Sessions

| # | Deliverable |
|---|---|
| 1 | `AIError` + classifier (HTTP status/body, timeouts, InsufficientMemory, ModelLoad), tests in `core` |
| 2 | Retry/fallback policy; key rotation wired in; `ModelCooldownStore` behind a `core` interface |
| 3 | `ResourceBudget` + `canRun` over LLM / E5 / Whisper / Vosk / AICore reserve |
| 4–5 | `AIProvider`: one OpenAI-compatible provider for Groq, Gemini, Mistral, xAI, OpenRouter, custom; `GigaChatProvider`; adapters for local llama.cpp and AICore |
| 6–7 | Execution engine in `core`: SINGLE + COMPARE; candidate building and compare mode moved out of `AppContainer` (which keeps only wiring). Characterization tests of the current fallback order and compare behaviour come first |
| 8 | `AIExecutionMetrics` + listener |
| 9–10 | Model catalog: discovery per provider, normalization, repo + remote catalog, merge, cache, statuses |
| 11 | Capability-based selection over the catalog, respecting enabled providers and status; manual model choice kept |
| 12–13 | Buffer for on-device regressions |
| — | `core` split from `core-chat`: `NodeExecutors`/`LlmMemoryExtractor` (memory/knowledge-aware pipeline wiring) moved to a new `core-chat` module, so `core` itself no longer depends on Mobile_mem0/`:commercial-memory` at all — routing, errors, the model catalog and the SINGLE/COMPARE engine are provably memory-free (`core`'s own JVM test suite now runs with zero memory imports on its classpath). `Orchestrator` takes a pre-built `PipelineEngine` instead of a `NodeExecutors`, so a caller with no use for memory (a translation-only IntelliVerse integration is the concrete case this was done for) never needs `core-chat` on its classpath. See MOBILE_MEM0_DEPENDENCY.md. Not one of the original 13 sessions — prep work for whichever Stage 2 session actually adds an IntelliVerse consumer, done early because it was low-risk (a mechanical move plus one constructor signature change, zero behavior change) and directly unblocks it. |

## Definition of done

- Chat (single and compare), Translation, voice/audio, memory/RAG and
  pipelines work as before, on CI and on the test devices.
- No existing cloud provider lost: Groq, Gemini, GigaChat, Mistral, xAI,
  OpenRouter, custom endpoint. OpenAI is optional — included only if it
  falls out of the OpenAI-compatible provider for free.
- Routing, fallback, retry and key rotation live in `core`, not `AppContainer`.
- Models go discover → normalize → verify → select; manual choice still works.
- Structured execution metrics exist.
- Nothing in the build depends on IntelliVerse.

## Out of scope (Stage 2+)

IntelliVerse integration; native Gemini provider (generateContent, File API);
public `ai-api` module and publishing; full resource lifecycle coordinator;
llama.cpp / Whisper rework; automatic use of newly discovered models.
