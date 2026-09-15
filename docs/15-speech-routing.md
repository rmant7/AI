# Speech routing architecture

A model-agnostic ASR routing layer: detect the spoken language continuously,
route each stretch of speech to the best available model for that language,
fall back to a multilingual model when the language is unknown, mixed, or
has no specialist. The model set (Whisper, Vosk, and whatever comes after —
Nemotron, Parakeet, Canary, an ONNX or GGUF or remote engine) is
configuration, not architecture: adding one means implementing and
registering `RegisteredSpeechModel`, never touching the router.

This is the foundation phase — interfaces plus one router implementation,
tested against fakes. Wiring real Vosk/Whisper into it, and a UI to exercise
it, are tracked separately (see the repo's own task list at the time this
was written).

## The pipeline

```
AudioSource
    ↓
StreamingRoutingSession.acceptAudio (transport-sized chunks, ~0.5-1s)
    ↓
rolling LID window (~2-5s) ──► LanguageIdentifier
    ↓
hysteresis (RoutingPolicy)
    ↓
SpeechModelRegistry.findCandidates(language)
    ↓
RegisteredSpeechModel.handle() (lazy load) ──► SpeechModelHandle.startStreaming()
    ↓
StreamingSpeechSession (the model's own utterance/partial-vs-final boundaries)
    ↓
TranscriptSegment(language, modelId, text, ...)
```

File transcription and the microphone are the same path: neither
`StreamingRoutingSession.acceptAudio` nor anything below it knows or cares
where a `ShortArray` chunk came from.

## New types (`core/src/main/kotlin/ai/localstudio/core/speech/`)

- **`Language`** — closed enum (`RU`, `EN`, `HE`, `UNKNOWN`). Grows by
  adding an entry; nothing downstream special-cases a value by name.
- **`LanguageIdentifier`** — `suspend fun identify(audio: AudioChunk): LanguageIdResult`.
  `LanguageIdResult` carries `alternatives` and `isMixed`, not just one
  language — code-switched speech is a first-class result, not an edge
  case bolted on later. Swappable: a cheap heuristic, a dedicated LID
  model, or an ASR model's own language-detection output are all just a
  `LanguageIdentifier`.
- **`SpeechModelCapabilities`** / **`SpeechModelInfo`** — what a model can
  do (languages, streaming, file transcription, auto-detection, code-
  switching) and how it should be ranked (`priority`). `AsrEngineType` is
  diagnostic only — the router never branches on it.
- **`RegisteredSpeechModel`** — `info: SpeechModelInfo` plus
  `suspend fun handle(): SpeechModelHandle`, lazy. Deliberately a separate
  type from `SpeechModelHandle` itself (see its own doc comment for why):
  this is the actual extension seam.
- **`SpeechModelRegistry`** — register/unregister/get/all/`findCandidates`.
  `findCandidates` ranks a language-matching specialist ahead of a
  generalist (empty `languages` = "any") regardless of priority number,
  then by priority within each tier.
- **`RoutingPolicy`** — `minLanguageConfidence`, `switchConfidence`,
  `minStableWindows`, `allowModelSwitching`, `fallbackModelId`. A different
  policy (fast/accuracy/offline/battery/benchmark) is a new value of this
  data class, never a new branch in the router.
- **`StreamingSpeechRouter`** / **`StreamingRoutingSession`** —
  `start()` → a session with `acceptAudio`/`finish`/`cancel`/`segments`/
  `decisions`.
- **`DefaultStreamingSpeechRouter`** — the implementation. See its own doc
  comment for the two audio-chunk sizes (transport chunks vs. the larger
  LID window) and the threading model (audio capture never blocks on LID
  or model loading — see below).
- **`RoutingDecision`** — one per LID evaluation, not only switches
  ("stayed on RU" is as useful during the experimental phase as "switched
  to EN" — see its own doc comment).

`TranscriptSegment` gained two optional fields, `language` and `modelId`,
both null for every existing producer (file transcription, the existing
Whisper/Vosk mic sessions) — only router-produced segments set them.

## Hysteresis

The router does not switch on every LID window. Example, with the default
policy (`switchConfidence = 0.75`, `minStableWindows = 2`):

```
current = RU
RU 0.90  -> confirmed RU
EN 0.58  -> below switchConfidence, stay RU
EN 0.91  -> candidate, window 1/2, stay RU
EN 0.91  -> candidate, window 2/2 -> switch to EN
```

A model switch calls the outgoing model's `finish()` (flushing its last
utterance, not discarding it) before starting the new one; the outgoing
model's segments still reach `segments` — the switch does not truncate
mid-utterance text (see `DefaultStreamingRoutingSession.collectorJobs`'
own doc comment for why the two models' segment collectors run
concurrently through a switch rather than the old one being cancelled).

## Threading and backpressure

`acceptAudio` only enqueues into a bounded, non-suspending inbox — LID,
model loading and switching all happen on one background coroutine per
session. A caller that outpaces processing has its newest chunk dropped
rather than blocked, and `droppedChunkCount` makes that visible instead of
silent.

## Fallback

- No specialist registered for the detected language → fallback.
- LID confidence below `minLanguageConfidence` (first pick) or mixed
  result → fallback, regardless of confidence.
- A specialist's `handle()` or `startStreaming()` throws → the router
  tries the next candidate, then the configured fallback, automatically.
- Everything fails → `lastError` is set on the session (same convention as
  `WhisperCppMicSession`/`VoskSpeechRecognizer`) rather than crashing the
  session.

## Tests

`core/src/test/kotlin/ai/localstudio/core/speech/` — `Fakes.kt` (no real
model, no real LID) plus `DefaultStreamingSpeechRouterTest.kt`, covering:
RU/EN specialist routing, HE/unknown/mixed fallback, a stable RU→EN switch,
a brief incorrect reading that doesn't switch, a specialist that fails to
start, a specialist whose `handle()` throws (not installed), registry
register/unregister, one session shape serving both a mic-style and a
file-style caller, and routing correctness under unfamiliar model ids (the
structural check that nothing is secretly keyed to "vosk"/"whisper" by
name).

## Real wiring (device-testable)

- **`ai.localstudio.app.whisper.WhisperRegisteredSpeechModel`** — Whisper as
  the fallback, reusing `WhisperCppRuntime`'s existing load path (same
  reload-on-seed-change policy as `WhisperCppMicSession`).
- **`ai.localstudio.app.vosk.VoskRegisteredSpeechModel`/`VoskSpeechModel`** —
  a *new*, push-based `SpeechModelHandle`/`StreamingSpeechSession` over
  `org.vosk.Model`/`Recognizer`, distinct from the pull-based
  `VoskSpeechRecognizer` the standalone Vosk spike (docs/14) already uses:
  the router drives audio in via `acceptAudio`, so the model side has to
  accept pushed chunks, not pull from its own `AudioSource`. Uses the same
  installed `vosk-small-ru`/`vosk-small-en` seeds as the spike.
- **`ai.localstudio.app.whisper.WhisperLanguageIdentifier`** — the "initial
  implementation may use Whisper itself" LID: a quick whisper.cpp
  auto-language pass over the LID window, classified by which Unicode
  script (Cyrillic/Hebrew/Latin) dominates the resulting text. A real
  proxy, not a placeholder — but not a dedicated LID model's output
  either, and not cheap (a full whisper pass per window, hence
  `AppContainer`'s several-second `lidStrideMs`).
- **`AppContainer.speechModelRegistry`/`speechRouter`** — wires
  `vosk-small-ru` (RU), `vosk-small-en` (EN), Whisper (fallback) with
  `RoutingPolicy(fallbackModelId = "whisper-fallback")`. Both Vosk
  specialists and the Whisper fallback are released under memory pressure
  the same way every other engine in this app already is.
- **`TranscribeActivity`**'s "LIVE MIC — LANGUAGE ROUTER (EXPERIMENTAL)"
  section — mutually exclusive with the other two mic sections (one
  microphone), each line prefixed `[language][modelId]` so a routing
  decision is visible next to its output, not just the clean text.

## Still not done

ML-based model selection, benchmark-driven routing, dynamic model
downloading, model ensembles, external JSON configuration (the registry is
in-process/in-memory only), and a benchmark-mode UI — all explicitly out
of scope per the original request.
