# STT Benchmark Mode

Objectively compares multiple local speech-to-text backends against the
same set of audio files: same input, same decoding parameters where the
engine allows it, structured JSON output, so a backend swap or a model
update can be judged by numbers instead of impression.

## Priority (as scoped for this round)

1. Benchmark infrastructure (this doc's own architecture) — done.
2. whisper.cpp as the first, "current backend" `TranscriptionEngine` — done.
3. CTranslate2 as a second backend — **not implemented this round**; see
   [CTranslate2 feasibility](#ctranslate2-android-feasibility) below for why,
   and the recommended minimal spike if it's ever picked back up.
4. Other backends — not started.
5. Real-time microphone integration on top of the same `TranscriptionEngine`
   abstraction — deliberately deferred; this round is files-only, per the
   scope this was built to.

Deliberately no complex UI: one screen (pick folder → confirm → run →
summary table), because the actual deliverable is trustworthy measurements
and raw results saved to disk, not a polished screen.

## Architecture

```
                     Audio Files (this round's scope)
                              ↓
                       Benchmark Runner (core, engine-agnostic)
                              ↓
                       TranscriptionEngine
                 ┌────────────┼────────────┬───────────┐
         Whisper.cpp     CTranslate2   other engines  (future)
          (current)      (not built)

                     ── later, unbuilt ──
        Microphone → Audio Pipeline → TranscriptionEngine → Selected Backend
```

`TranscriptionEngine`/`TranscriptionEngineSession`
(`core/src/main/kotlin/ai/localstudio/core/benchmark/TranscriptionEngine.kt`)
is a new, deliberately separate interface from
`ai.localstudio.core.runtime.SpeechModelHandle`/
`ai.localstudio.core.speech.RegisteredSpeechModel` (the router's own
contract): a benchmark needs identity metadata neither of those interfaces
carries (backend id, display name, version, model id, precision) and an
explicit, separately-timed `warmUp()` step the router has no place for.
`BenchmarkRunner` (`core`, no Android dependency) knows only this interface
— adding a new backend later means implementing it, never touching the
runner, the same seam `RegisteredSpeechModel` already establishes for the
language router.

`AudioRef`/`Transcript` (`core/src/main/kotlin/ai/localstudio/core/model/Media.kt`)
are reused as-is rather than inventing benchmark-specific audio/result
types.

### Files

- `core/.../benchmark/TranscriptionEngine.kt` — the interface.
- `core/.../benchmark/BenchmarkModels.kt` — `@Serializable` result/report shapes.
- `core/.../benchmark/BenchmarkRunner.kt` — engine-agnostic orchestration.
- `core/.../benchmark/BenchmarkSummary.kt` — the avg/median/RTF summary table math.
- `app/.../whisper/WhisperCppTranscriptionEngine.kt` — whisper.cpp as a `TranscriptionEngine`.
- `app/.../benchmark/BenchmarkFileScanner.kt` — folder scan + per-file audio metadata (`MediaExtractor`).
- `app/.../benchmark/BenchmarkReportStore.kt` — attaches device/app metadata, saves JSON.
- `app/.../benchmark/BenchmarkOrchestrator.kt` — app-scoped run owner + `BenchmarkUiState`.
- `app/BenchmarkActivity.kt` — the one screen.

### Identical input, honestly

Every engine in one run sees the same file, sampled at whatever rate the
source file already is (no engine in this app resamples independently, so
this is inherently shared, not something the runner has to force) and the
same task (`"transcribe"`, the only task whisper.cpp is exercised for here
— recorded on `BenchmarkReport.sharedTask` rather than left implicit).
Where a parameter genuinely can't be forced identical across engines (the
whisper.cpp size/quantization ladder already exercises this: each size has
its own `precision`, some quantized and some not — see `WhisperModels`),
the actual value is recorded per engine in `BenchmarkRunMetrics` rather
than pretended to be unified — `threads`, `precision`, and
`forcedLanguage`/`detectedLanguage` all work this way.

### Warm-up, load time, and steady-state kept separate

Every engine is loaded and warmed up (one throwaway inference) before any
of its files are transcribed, and a cold-start cost never leaks into the
per-file steady-state timings. `BenchmarkEngineSummary` carries
`modelLoadMs` and `warmInferenceMs` separately. An engine whose load or
warm-up fails does not abort the whole run — its rows simply report
`BenchmarkStatus.ERROR` for every file, so a backend that couldn't be set
up shows up as a documented failure, not a silent gap in a report read
weeks later.

**Warm-up runs on a fixed, bundled sample** (`WarmupSample`, a ~1.5s
synthetic tone shipped as `assets/warmup_sample.wav`), not on one of the
user's own scanned files. Content is irrelevant to what warm-up is for —
priming native buffers/thread pools and paging in the model's own weights
(mmap page faults, CPU governor ramp-up) — only running the same inference
code path matters. A real device report is why this changed from an
earlier "pick one of the scanned files" approach: with a folder scanned in
filesystem order, `files.firstOrNull()` landed on a long Zoom recording,
and warm-up quietly became a multi-minute full transcription before any
real measurement even started. A fixed sample is also what makes
`warmInferenceMs` comparable *across separate runs* — its cost no longer
depends on whichever file happened to be shortest in whatever folder was
picked that time. `BenchmarkRunner.run`'s `warmupSample` parameter still
falls back to the shortest-known-duration file in `files` when the caller
doesn't supply one (it has no Android dependency to source a bundled asset
from itself).

`WarmupSample` is shared, not benchmark-only: `TranscribeActivity` uses the
exact same asset to silently pre-load and warm the currently-selected
model the instant its screen opens (`warmUpSelectedModel()`), so a real
user tapping "Transcribe" right after doesn't pay the cold-start cost
visibly on their first file — it was already paid in the background while
they were still picking one.

That proactive warm-up **skips itself while a benchmark is running**
(checks `AppContainer.benchmarkOrchestrator.state`). Every whisper.cpp
native call in this app, regardless of which engine instance it belongs
to, goes through the same process-wide `WhisperBridge.nativeOpMutex` — a
real device report showed a benchmark's own model load taking 44 seconds
for a size that had loaded in ~1-2s earlier in the exact same run, strongly
suggesting this exact warm-up call was competing for that lock at the same
time (most likely `TranscribeActivity` open or recreated concurrently).
This screen's own warm-up is a nice-to-have; a benchmark run in progress
is not something it should ever be allowed to stall.

### One engine resident at a time

`BenchmarkRunner` loads an engine, runs it against every file, and
**releases it before the next engine's own load starts** — never two
engines' sessions held open simultaneously. This matters concretely for
Whisper's own size ladder: `AppContainer.transcriptionEngines` compares
every *installed* Whisper size (Tiny through Large), and holding several
of those resident at once — up to ~2.9GB of weights alone across all six,
before any inference buffers — is real memory pressure on a phone that's
also running everything else the user has open. Sequencing one model at a
time keeps peak memory to roughly one model's own footprint regardless of
how many sizes are being compared.

Order is deliberate, not scan order: **Tiny first** (cheapest, fastest way
to confirm the whole run works before committing to a long one), then
**largest-to-smallest** through the rest — the run's single biggest,
riskiest load happens early, right after a known-good baseline, rather
than last, after whatever memory pressure the smaller models already
added.

### What's measured

Per (file, engine) pair, in `BenchmarkRunMetrics`: backend id/version,
model id, precision, thread count, forced/detected language, processing
time, RTF (`processingMs / durationMs`, null rather than a divide-by-zero
placeholder when duration is unknown), a memory reading, status, error
message, and the actual transcript text (so quality, not just speed, can
be compared later — this is why the JSON carries full transcripts instead
of only numbers).

**Memory is an honest approximation, not a true peak.** `memoryMb` is
`Debug.getNativeHeapAllocatedSize()` sampled once, immediately after each
`transcribe()` call returns — a snapshot of native heap *after* the call,
not a continuously-sampled peak *during* inference. A true peak would need
a background sampling thread polling throughout each transcription; that
wasn't built this round, so `memoryMb` should be read as "at least this
much," not "the peak." CPU usage, temperature/throttling indicators, and a
true peak-during-inference figure are not collected at all.

**Detected language has a known gap.** `WhisperCppSpeechModel.transcribe()`
echoes back the *input* `language` argument on `Transcript.language`, not
whisper.cpp's own auto-detected result — a pre-existing limitation of that
class, not introduced by the benchmark. `WhisperCppTranscriptionEngine`
calls it with `language = null` (auto), so `detectedLanguage` in benchmark
results reads `null` for every run rather than a fabricated guess.

### Reproducibility

`BenchmarkReport` carries `benchmarkVersion`, app version (name + build
number), device info (model, Android version/API level, RAM, ABI, CPU core
count), start/finish timestamps, every file's own metadata, and every
(file, engine) result — enough to compare a run from weeks ago against a
fresh one after a model or engine update, per the original requirement.

### Output

1. One plain-text file per (engine, audio file), under
   `Download/Transcripts/benchmark/<modelId>/<audio file name>.txt` —
   containing a short metrics header (status, processing time, RTF, native
   heap reading) followed by the transcript itself, or the error message
   for a failed run. Organized **by model**, not by source file: this is
   what makes "what did Tiny produce for this recording vs. what did Large
   produce" a matter of opening two folders side by side, without digging
   through the combined JSON. **Written incrementally, per file**
   (`BenchmarkReportStore.saveFileResult`, called from `BenchmarkRunner`'s
   `onFileComplete`) — the moment each individual file finishes, not
   batched by engine and not batched to the end of the whole run. A real
   device report is why: with everything saved only once at the very end, a
   run comparing several GB-scale Whisper sizes produced *zero* inspectable
   output for over half an hour, all-or-nothing — and even "once per
   engine" still meant a long wait, since a single *file* can itself take
   several minutes on a large model.
2. The full JSON report, once the whole run finishes (`BenchmarkReportStore.save`):
   internally to `filesDir/benchmarks/benchmark-<timestamp>.json` always
   (what `BenchmarkActivity`'s own Share button reads, via the app's
   existing `FileProvider`), and mirrored to `Download/Transcripts/benchmark/`
   via `MediaStore` on API 29+ — the single reproducible record of the
   whole run, including every engine's own summary (load/warm-up time,
   failures).

Every MediaStore write (both the per-engine `.txt` files and the final
JSON) logs its own failure individually, tag `BENCHMARK` — a real device
report showed why this matters: a run that reported "Done" on screen left
no folder on disk at all, and a bare `runCatching` with no logging gave no
way to tell why.

### Summary table

`BenchmarkSummary.summarize(report)` groups results by `backendId` and
computes: file count, success/error count, average RTF, **median RTF**,
average processing time, total processing time, and peak memory (max of
the per-run `memoryMb` samples — see the honesty note above on what that
actually represents). Median is computed explicitly alongside average per
the original requirement: a single slow outlier (one large file, one
thermal-throttled run) skews an average far more than a median, and this
exists specifically so one bad file doesn't misrepresent an otherwise-fine
backend.

## CTranslate2 Android feasibility

Researched before writing any CTranslate2 integration code, per the
explicit instruction not to assume desktop `faster-whisper`/CTranslate2
can simply be ported to Android.

**Verdict: full CTranslate2 integration into this app is not currently
feasible without substantial, unproven native engineering work. It should
not be attempted as a direct "integrate it" task.**

### Findings

1. **CMake/NDK support** (High confidence): none. CTranslate2's own
   `CMakeLists.txt` has zero Android/NDK references. Two open, unresolved
   upstream GitHub issues document real, failed/abandoned attempts:
   [OpenNMT/CTranslate2#1683](https://github.com/OpenNMT/CTranslate2/issues/1683)
   ("there is no way for android build in this project," zero replies) and
   [OpenNMT/CTranslate2#1848](https://github.com/OpenNMT/CTranslate2/issues/1848)
   (got a `.so` on a PC, failed at Android app integration, unresolved). No
   working example or fork was found anywhere.
2. **Native dependencies**: `WITH_MKL` (Intel MKL, default on, x86-only) and
   `WITH_DNNL` (oneDNN, x86) are the default acceleration paths; the
   ARM-relevant one, `WITH_RUY` (Google's Ruy matmul library), defaults
   off. GPU flags are irrelevant to a phone. A bundled `neon_mathfun.h`
   confirms an ARM NEON code path exists, so an ARM build (`WITH_MKL=OFF
   -DWITH_RUY=ON`) is real, but non-trivial — ARM isn't the well-trodden
   default path, and there's evidence of real friction even on easier ARM
   targets (Raspberry Pi build reports of hangs/OOMs; ARM64 CI builds that
   skip CUDA support entirely).
3. **JNI/C API surface** (High confidence): none exists. Only Python
   (pybind11) and raw C++ bindings are provided. A full JNI wrapper would
   need to be written from scratch — more work than whisper.cpp's own
   flatter C API, since CTranslate2's object model (Translator/Generator/
   StorageView/batching) is richer.
4. **Whisper large-v3 + quantization on ARM CPU**: Whisper is a supported
   architecture via `ct2-transformers-converter`, but the quantization
   matrix narrows hard on a CPU-only ARM target — only plain **int8 via
   Ruy** (or unquantized float32) is usable; int16 is MKL/x86-only, and
   float16/bfloat16/int4-AWQ are all GPU-only. Most of CTranslate2's own
   marquee performance features don't apply on a phone.
5. **Model/library size**: `Systran/faster-whisper-large-v3` fp16
   `model.bin` is 3.09GB (verified); an int8 conversion would land roughly
   1.5-1.7GB (estimated — not independently verified). Native library size
   for Android is unverified; as a proxy, the official manylinux aarch64
   Python wheel is 16.9MB vs. 39.5MB for x86-64 (which bundles MKL) — an
   Android `.so` would likely land in the 15-40MB range, explicitly an
   extrapolation, not a measured number.
6. **RAM at inference** (Medium confidence, non-authoritative sources):
   roughly 1.5GB (int8) to 2.5GB (float32 fallback) minimum, likely more
   once KV-cache, beam search, and audio buffers are accounted for — no
   authoritative first-party figure exists for large-v3 on ARM/mobile.
7. **Prior art on Android**: none found anywhere (forums, blogs, Stack
   Overflow, XDA, Reddit all checked) beyond the two unresolved GitHub
   issues above. One data point that doesn't transfer: CTranslate2's macOS
   ARM64 (Apple Silicon, Darwin — not NDK/Bionic) wheels build without
   OpenMP using a fallback threading path.

### Why this outweighs the effort

No Android-aware build system, two independent real-world failed attempts
already on record, zero JNI surface to build from, a narrow ARM-CPU-usable
feature set (int8/Ruy only — most of CTranslate2's actual speed advantage
comes from MKL/oneDNN on x86 or CUDA on GPU, neither of which applies on an
Android phone's CPU), and a larger model/RAM footprint than whisper.cpp's
mature, purpose-built mobile quantization already integrated in this app.
The performance case for switching is itself unproven on this exact
target — CTranslate2's real-world speed edge doesn't obviously survive the
move to an ARM CPU at all.

### Recommended minimal experimental path, if this is ever revisited

Bounded by kill criteria at each step, not committed as a roadmap item:

1. Standalone spike, outside this app's repo: cross-compile plain upstream
   CTranslate2 with the NDK CMake toolchain (`WITH_MKL=OFF -DWITH_RUY=ON
   -DWITH_OPENMP=OFF`). Success criterion: a valid `arm64-v8a` `.so`,
   nothing more.
2. If that succeeds: validate it actually runs via `adb shell` with a
   small, non-Whisper int8 test model and a minimal C++ test binary —
   before writing any JNI at all.
3. Only if 1-2 succeed: write a minimal JNI wrapper for a single
   transcription call, and benchmark it against the existing whisper.cpp
   path (this benchmark's own `TranscriptionEngine` interface) on a real
   device.
4. Treat the whole thing as a bounded research spike with zero prior art
   to lean on, not a scheduled feature.

## Hebrew quality: ivrit.ai, not a new backend

Stock Whisper's Hebrew transcription quality is mediocre — this app's own
router falls back to it for Hebrew (see docs/15-speech-routing.md) purely
because no better specialist existed. ivrit.ai
(https://huggingface.co/ivrit-ai) publishes continued-training fine-tunes
of Whisper large-v3/large-v3-turbo on Hebrew (crowd-transcribe, crowd-
recital, Knesset session data) as ready-made ggml `.bin` files — loadable
by the exact same `WhisperCppTranscriptionEngine`/`WhisperCppRuntime` path
every other Whisper size already uses, zero new native integration. Their
own public leaderboard
(https://huggingface.co/spaces/ivrit-ai/hebrew-transcription-leaderboard)
independently shows it beating vanilla Whisper on Hebrew WER.

Added to `WhisperModels.SEEDS` as `whisper-ivrit-large-turbo` (1.62GB) and
`whisper-ivrit-large` (3.1GB) — both **fp16, not quantized** (ivrit.ai
publishes no q5_0/q8_0 ggml variant as of this writing), so noticeably
heavier than this catalog's own quantized stock `large`/`large-turbo`
entries; `whisper-ivrit-large` in particular is the single largest model
this catalog offers and a real memory-pressure risk on its own (see
`transcriptionEngines`' tiny-first-then-largest ordering, which exists
partly for exactly this kind of entry). URLs/sizes were cross-verified via
ivrit.ai's own `asr-training` GitHub repo and matching SHA-256 hashes
across several independent third-party consumers of the same file — not a
live HTTP HEAD, since this project's own dev environment blocks
`huggingface.co` at the network egress layer. Worth a one-time on-device
sanity check (does it actually load) the first time either is installed.

## sherpa-onnx: researched, not integrated this round

Investigated as a second *backend* (unlike ivrit.ai, which is just another
model on the existing whisper.cpp backend) after a suggestion to look at
NVIDIA's multilingual models. Findings, for whoever picks this up next:

- **Android integration is real**, comparable effort to this app's own
  whisper.cpp JNI work — prebuilt per-release `.so` archives for
  arm64-v8a/armeabi-v7a/x86/x86_64 plus a genuine Kotlin API source tree
  you copy in (https://github.com/k2-fsa/sherpa-onnx). No official Maven/AAR
  artifact from the project itself, though.
- **NVIDIA models it exposes don't cover Hebrew.** Parakeet is English-only
  (aside from `parakeet-tdt-0.6b-v3`'s 25 European languages, which does
  include Russian); Canary covers EN/DE/FR/ES; none target Hebrew.
- **Qwen3-ASR 0.6B** (937MB int8, not the ~1.95GB first assumed) supports
  ~52 languages including Russian — but explicitly **not** Hebrew (a
  Hebrew-support request is an open, unresolved upstream issue).
- **Omnilingual ASR 300M** (Meta FAIR, ~348MB int8,
  `csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-2025-11-12`)
  is the one model in this whole investigation that actually covers Hebrew
  (`heb_Hebr`, confirmed present in the model's own primary language list)
  — but it's CTC with no language model, so no punctuation or casing, and
  no Hebrew-specific quality numbers exist anywhere yet. A real candidate,
  not a strong one.
- **QNN (Qualcomm) acceleration is inapplicable to this app's actual test
  device.** The Pixel 10 Pro runs a Google Tensor G5, not a Qualcomm
  Snapdragon — QNN model files are compiled per-Snapdragon-SoC and require
  Qualcomm's Hexagon NPU, which Tensor chips don't have. This backend
  cannot run on the device this project actually tests against, full stop.
- **Memory model is a real concern, not just a size number.** Unlike
  ggml/whisper.cpp (mmap-based, so the OS can reclaim pages under
  pressure), sherpa-onnx's ONNX Runtime path does not mmap model weights
  (an mmap request is an open, unimplemented upstream issue) and appears to
  load substantially into heap — a 937MB model is a materially bigger OOM
  risk than its on-disk size alone would suggest, on a device that has
  already hit real memory pressure during this project's own testing.

**Verdict**: worth a future round specifically for Omnilingual-300M (the
only real Hebrew angle) or Parakeet/GigaAM for Russian — not for QNN speed
or Qwen3-ASR's Hebrew coverage, both of which turned out not to apply here.
Not started this round: ivrit.ai's zero-integration-cost fix was the
obviously cheaper first move for the same Hebrew problem.

## What's deliberately not done this round

- CTranslate2, sherpa-onnx, or any other second *backend* — see above.
- Real-time microphone benchmarking — the spec explicitly asked for
  files-first; microphone mode reuses the same `TranscriptionEngine`
  abstraction later, per the architecture diagram above.
- A true continuously-sampled memory/CPU peak during inference, or
  temperature/throttling indicators — `memoryMb`'s honest scope is
  documented above rather than silently overstated.
