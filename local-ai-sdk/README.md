# local-ai-sdk

The one entry point an app uses for on-device AI: `LocalAi`. Pure Kotlin,
no Android types; `testing/FakeLocalAi` keeps the same contract for a
caller's own tests.

## Contract

- **Offered is not proven.** `LocalModel.capabilities` is what the installed
  files allow (VISION only with a projector). `LocalModel.verified` is what a
  check on this device observed. Rely on `ModelQuery.proven(capability)` —
  a current PASS.
- **A check is evidence for exactly one context**: these bytes (repository,
  commit, main file, projector — or the files' sha256 for an install with no
  recorded source), this device model, this runtime build, this set of
  questions. Change any one and the result is STALE: not a pass, not
  "never checked". `verify(modelId)` runs a new check.
- **Capability ≠ performance.** PASS means the model gave the right answers
  to every question of the check, however slowly. Qwen2.5-VL-7B passes
  VISION on a Pixel 10 Pro at well under 1 tok/s on the first, mapped load.
  Whether that is pleasant to use is a separate measurement (time to first
  token, tokens/s, RAM), kept with the check but never folded into
  PASS/FAIL.
- **FAIL is the answers, not the runtime.** A model whose projector loads
  and runs but answers an image question wrongly fails VISION
  (Index-Translate-2B: one image right, two images in one turn wrong). It is
  not promoted to PASS because the runtime works.
- **Images are never silently dropped.** Images to a model that cannot see
  fail with `LocalAiException.ImageNotSeen`, never with a text answer that
  pretends it saw them. A model that takes fewer images per request than
  it was given fails the same way (Gemini Nano: one image per request).
- **Sources**: CATALOG (shipped with the app), DISCOVERED (found by search,
  taken in after a check), CUSTOM (added by the user), SYSTEM (part of the
  phone: Gemini Nano through AICore — no file, size 0, offered only while
  the system reports it ready; its checks are tied to AICore's version, and
  an AICore update makes them STALE).
- Results are PASS / FAIL / NOT_TESTED / STALE. Per-question evidence and why
  a step failed (model answer, runtime, resource) are internal to the app,
  not part of this API.
