# Vosk ASR spike

A small, isolated experiment: is `com.alphacephei:vosk-android` (Kaldi-based,
genuinely streaming, Maven Central) a better live-mic ASR than whisper.cpp's
re-transcribe-the-growing-buffer approach? Whisper is left completely
untouched — this is a second, independent path in `TranscribeActivity`, not a
rewrite of anything in docs/13-asr-pipeline-migration.md.

Explicitly **not** in scope for this spike: a benchmark suite, RTF
measurements, sherpa-onnx, a new ASR abstraction for the whole project, a
model manager as elaborate as Whisper's (RAM-fit labels, freshness checks,
…). Several Vosk model variants *are* in scope — real device testing turned
out to need it (see below) — but the catalogue itself stays a short,
hand-picked list, not a general catalog system.

## Known, likely-disqualifying limitation: no code-switching

A single `Recognizer` is built against one model's fixed decoding graph
(lexicon + language model for exactly one language) — there is no
multilingual Vosk model and no way to switch languages *within* an
utterance the way Whisper's shared multilingual vocabulary lets it degrade
gracefully on code-switched speech. Running several `Recognizer`s in
parallel (one per language) would only ever let you pick a result per
*utterance*, never resolve a sentence that mixes languages word-to-word —
and that logic doesn't exist here. Separately, Hebrew does not appear in
Vosk's own language list at all
(<https://alphacephei.com/vosk/models>), so a third language in the mix
isn't just a code-switching problem — there may be no Hebrew model to load
in the first place. If real speech mixes languages within a sentence (not
just switches between separate recordings), this is very likely a hard
blocker regardless of the rest of this spike's verdict — worth confirming
against the actual models list before spending more time on this path.

## What's new

- **Dependency**: `implementation("com.alphacephei:vosk-android:0.3.75")` in
  `app/build.gradle.kts` — Maven Central only, no local AAR, no custom JNI.
- **`ai.localstudio.app.vosk.VoskSpeechRecognizer`** — the adapter. Isolates
  `org.vosk.Model`/`org.vosk.Recognizer` lifecycle and Vosk's JSON result
  parsing behind a `Flow<ai.localstudio.core.model.Transcript>`. Feeds an
  existing `AudioSource` (`MicrophoneAudioSource`, unchanged — same
  AudioRecord/16kHz-mono capture Whisper's mic path already uses) straight
  into `Recognizer.acceptWaveForm` one chunk at a time. Every emission
  carries the *full* session text so far, so the UI needs no revision
  bookkeeping.
- **`ai.localstudio.app.vosk.VoskModels`** — the small, hand-picked
  catalogue (`vosk-small-ru`, `vosk-small-en`, and a larger, more-accurate
  `vosk-ru`). Each seed carries a *list* of URLs, tried in order —
  <https://alphacephei.com/vosk/models> first, then a Hugging Face mirror —
  since `alphacephei.com` alone has been reported unreachable (DNS not
  resolving, VPN or not) on a real device.
- **`ai.localstudio.app.vosk.VoskModelStore`** — one directory per seed
  under app-private storage, plus `extract()` to unzip a downloaded model
  into it (stripping the single top-level directory every official archive
  wraps its contents in).
- **`ai.localstudio.app.vosk.VoskDownloads`** — downloads (via the same
  `ModelDownloader` Whisper/chat models use) then unzips, per seed, so
  several models can download at once — mirrors `WhisperDownloads`.
- **Models → Voice tab**: a second catalogue, "Vosk (spike)", below the
  Whisper one — Download/Use/Delete per model, same as Whisper's own rows.
  `Settings.voskModelId` remembers which one is selected, the same way
  `Settings.whisperModelId` does for Whisper.
- **`TranscribeActivity`**: a second "LIVE MIC — VOSK (SPIKE)" section,
  below the existing Whisper one, with its own Start/Stop button and
  transcript box. Starting one stops the other (they'd otherwise fight over
  the same microphone). Shows a plain "No Vosk model installed — download
  one on the Models screen first" message (not a truncating Toast — see
  below) if none of `VoskModels.SEEDS` is installed yet.
- **`AppContainer.voskRecognizer`**/**`voskDownloads`** — shared instances,
  the model freed under memory pressure the same way `whisperMicSession`
  already is.
- **Error dialog, not a Toast**: a mic-session error (Whisper's or Vosk's)
  used to show as a Toast, which truncates long text and disappears on its
  own — exactly wrong for something you might need to read in full or copy
  out. It's now a dialog: full text, selectable, an explicit Copy button,
  stays open until dismissed.

## Device smoke test (Pixel 10 Pro — manual, not automated)

No benchmark harness for this — open **Models → Voice**, scroll to the
"Vosk (spike)" section, download `Vosk Small — Russian`, then **Use** it.
Back on **Transcribe**, use the "LIVE MIC — VOSK (SPIKE)" section, and
check:

1. Model loads (no crash/error toast on first Start).
2. Mic starts.
3. Speak a sentence in Russian.
4. Partial text appears *during* speech (e.g. "Я хочу завтра").
5. Partial keeps updating as you keep speaking ("Я хочу завтра позвонить" →
   "…позвонить Ивану").
6. After Stop, a final result appears (replaces/extends the partial).
7. No tens-of-seconds delay anywhere in the above.
8. No crash, on a missing model, mid-recording, or otherwise.
9. Repeated Start/Stop works (stop, start again, stop again).
10. Model releases correctly (leaving the screen / backgrounding the app
    doesn't leave it resident forever — same memory-pressure release path
    as Whisper's).

## Report template

After the device test, fill in:

```
Vosk:
- startup: OK/FAIL
- streaming partial: OK/FAIL
- latency субъективно: ...
- Russian transcription quality: ...
- final result: ...
- memory/lifecycle: ...
- integration difficulty: ...

Recommendation:
USE / DON'T USE
```

- **USE** → next step: make Vosk the main live-ASR path, keep whisper.cpp as
  the slower/high-quality final pass (the file-transcription side of
  `TranscribeActivity` already does this well).
- **DON'T USE** → nothing in Whisper needs touching; move on to the next
  streaming-ASR candidate from docs/13-asr-pipeline-migration.md's Phase 4
  research (sherpa-onnx was the other one identified, with its own
  integration-risk caveats noted there).
