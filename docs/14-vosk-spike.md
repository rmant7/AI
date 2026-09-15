# Vosk ASR spike

A small, isolated experiment: is `com.alphacephei:vosk-android` (Kaldi-based,
genuinely streaming, Maven Central) a better live-mic ASR than whisper.cpp's
re-transcribe-the-growing-buffer approach? Whisper is left completely
untouched — this is a second, independent path in `TranscribeActivity`, not a
rewrite of anything in docs/13-asr-pipeline-migration.md.

Explicitly **not** in scope for this spike: a benchmark suite, RTF
measurements, sherpa-onnx, a new ASR abstraction for the whole project,
multiple Vosk models, a real model manager/download UI. All of that is
gated on this spike's own verdict below.

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
- **`ai.localstudio.app.vosk.VoskModelStore`** — locates the model directory
  (see below). Not a downloader.
- **`TranscribeActivity`**: a second "LIVE MIC — VOSK (SPIKE)" section,
  below the existing Whisper one, with its own Start/Stop button and
  transcript box. Starting one stops the other (they'd otherwise fight over
  the same microphone).
- **`AppContainer.voskRecognizer`** — shared instance, freed under memory
  pressure the same way `whisperMicSession` already is.

## Provisioning the model (manual — this is a spike, not a download UI)

The model is a separate downloadable asset, not bundled in the APK, and
there is no in-app downloader yet. Push it manually:

1. Download a small Russian Vosk model from
   <https://alphacephei.com/vosk/models> — `vosk-model-small-ru-0.22`
   (~45 MB) is the right size class for a phone; do **not** use Tiny
   Whisper as a substitute, and don't reach for one of the larger
   (~1 GB+) Russian models for this first pass.
2. Unzip it — you get a directory (`am/`, `conf/`, `graph/`, `ivector/`, …).
3. Push that directory's *contents* to the path `VoskModelStore` expects:

   ```sh
   adb shell run-as ai.localstudio.app mkdir -p files/vosk-model-ru-small
   adb push vosk-model-small-ru-0.22/. /data/local/tmp/vosk-model-ru-small
   adb shell run-as ai.localstudio.app cp -r /data/local/tmp/vosk-model-ru-small/. files/vosk-model-ru-small/
   adb shell rm -rf /data/local/tmp/vosk-model-ru-small
   ```

   (`adb push` can't write directly into another app's private storage, so
   it goes through `/data/local/tmp` and `run-as` copies it the rest of the
   way — the same two-step shape already used for
   `WhisperBenchmarkTest`'s own provisioning.)
4. Confirm: `adb shell run-as ai.localstudio.app ls files/vosk-model-ru-small`
   should list `am`, `conf`, `graph`, … If the app instead shows the
   "No Vosk model found" toast, the directory is missing, empty, or in the
   wrong place — `VoskModelStore.modelDir()` is
   `context.filesDir/vosk-model-ru-small`.

## Device smoke test (Pixel 10 Pro — manual, not automated)

No benchmark harness for this — just run the app, open **Transcribe**, use
the "LIVE MIC — VOSK (SPIKE)" section, and check:

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
