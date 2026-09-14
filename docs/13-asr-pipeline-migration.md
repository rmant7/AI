# 13. ASR Pipeline Migration (claude-code → AI, Phase 1)

Код: `core/src/main/kotlin/ai/localstudio/core/audio/`,
`app/src/main/java/ai/localstudio/app/whisper/`,
`whisper/src/main/{kotlin,cpp}/`.

Перенос проверенных низкоуровневых решений из Android-приложения
`WhisperTranscriber` (`rmant7/claude-code`, ветка
`claude/android-whisper-transcription-3tuggu`) в архитектуру `AI`
(`ModelRuntime`/`SpeechModelHandle`/`Transcript`). Дальнейшая разработка ASR
ведётся только здесь; `claude-code` остаётся reference-реализацией и не
менялся в рамках этой работы.

## Аудит: что было в AI до этой работы

Важная поправка к исходной постановке задачи: README и `docs/12-audio.md`
описывают архитектуру *микрофонного* live dictation (VAD, `UtteranceAccumulator`,
перетранскрипция растущего буфера) — она была реализована и работала, но
**полностью в обход** `ModelRuntime`/`RuntimeManager`/router:

- `WhisperBridge`/`WhisperTranscriber`/`WhisperEngine`/`AudioRecorder`
  (`app/src/main/java/ai/localstudio/app/whisper/`) — рабочий ad hoc путь,
  вызывается напрямую из `ChatActivity`, не реализует `SpeechModelHandle`.
- `SpeechModelHandle`/`RuntimeKind.WHISPER_CPP` в `core` уже существовали, и
  `NodeExecutors.speechToText()` уже дергает `handle.transcribe()` через
  `RuntimeManager` — но **ни один класс не реализовывал `ModelRuntime` для
  `WHISPER_CPP`**. Любой пайплайн с узлом `SPEECH_TO_TEXT` падал с
  `ModelLoadException("No runtime registered for whisper_cpp")`.
- Даже после появления такого раннера: `RuntimeManager` в
  `AppContainer.buildOrchestrator` строился с `runtimes = mapOf(runtime.kind to runtime)`
  — **один** рантайм на оркестратор (тот, что обслуживает text generation).
  Второй capability (speech) физически не мог быть найден.
- `ModelSelector`/`ModelRegistry`, которые видит `NodeExecutors`, никогда не
  получали ни одной записи с `Capability.SPEECH_TO_TEXT` для локального
  `whisper_cpp` — `registry()` в `AppContainer` регистрировал такую запись
  только для `REMOTE_OPENAI` (в комментарии над этим кодом прямо сказано:
  «Voice input never actually goes through the pipeline in this app»).
- MediaExtractor/MediaCodec, decode PCM, resampling/downmix, folder/batch —
  в `AI` не было вообще. Единственный источник аудио — микрофон.

Итого: то, что в `claude-code` уже решено и проверено на устройстве
(decode, JNI, cancellation, build flags), в `AI` отсутствовало полностью;
а то немногое, что в `AI` уже существовало вокруг Whisper (JNI-мост,
VAD), было не интегрировано в архитектуру, ради которой затевался этот
перенос.

## Mapping

| claude-code (`WhisperTranscriber`) | AI |
|---|---|
| `AudioDecoder.kt` (MediaExtractor/MediaCodec, streaming, chunked) | `core/audio/AudioSource.kt` (абстракция) + `app/whisper/MediaCodecAudioSource.kt` (реализация) |
| `AudioDecoder`'s internal downmix/resample/PcmBuffer | `core/audio/PcmMath.kt`, `core/audio/PcmBuffer.kt` (чистый Kotlin, юнит-тесты) |
| `WhisperLib`/`WhisperModel` (JNI) | `whisper/.../WhisperBridge.kt` + `whisper_jni.cpp` (уже существовали в AI; добавлены `nativeCancel` и `SegmentSink`) |
| `whisper_jni.cpp`: `abort_callback`, `new_segment_callback` | тот же паттерн, перенесён в AI's `whisper_jni.cpp` |
| model loading (`WhisperLib.initContext`) | `WhisperCppRuntime.load()` (паттерн — `LlamaCppRuntime.load()`) |
| `TranscriptionService`/`Transcriber` (batch, callbacks) | `WhisperCppSpeechModel.transcribe()`/`transcribeStreaming()` |
| `LiveTranscriptionService` (mic streaming) | `WhisperCppSpeechModel.startStreaming()` — sliding-window shim, см. TODO |
| `requestCancel`/abort flag | `Interruptible.requestCancel()` + `cancelRequested` + `nativeCancel` |
| folder/batch processing | **не перенесено** — Phase 2, см. ниже |
| `TranscriptionResult`/`WhisperModel` | существующий `Transcript`/`TranscriptSegment` (`core/model/Media.kt`) — не дублировался |
| CMakeLists.txt (`-O3`, dotprod/fp16) | AI's `whisper/cpp/CMakeLists.txt` уже был на этом уровне (пиннутый `v1.9.3`, плюс `i8mm`) — менять не пришлось |

## Что сделано (Phase 1 — вертикальный срез)

Новое в `core` (чистый Kotlin, тестируется без Android SDK):

- `AudioSource` — платформонезависимый источник инкрементального PCM.
- `PcmMath` (downmix/resample) и `PcmBuffer` (growable PCM16-очередь) —
  вынесены из декодера, чтобы быть тестируемыми без устройства; тесты —
  `core/src/test/kotlin/ai/localstudio/core/audio/{PcmMathTest,PcmBufferTest}.kt`.
- `SpeechModelHandle.startStreaming()` + `StreamingSpeechSession` — второй,
  по-настоящему инкрементальный режим рядом с batch `transcribe()`. Все
  существующие реализации (`FakeSpeechModel`, `StubRuntime.StubSpeechModel`,
  `OpenAiRuntime.RemoteSpeechModel`) обновлены под новый интерфейс.

Новое в `whisper` (JNI):

- `WhisperBridge.nativeCancel` — флип атомарного флага, который
  whisper.cpp проверяет через `abort_callback` между шагами декодирования;
  сбрасывается в начале каждого `nativeTranscribe`, чтобы отменённый вызов
  не «протекал» в следующий (тот же паттерн, что в reference-реализации).
- `WhisperBridge.SegmentSink` — сегменты с таймстемпами доставляются через
  `new_segment_callback` по мере готовности, а не после `whisper_full`
  целиком.
- `cparams.flash_attn = true` при загрузке модели.
- `WhisperBridge.nativeOpMutex` — сериализация конкурентных нативных
  вызовов на одном handle (тот же паттерн, что `LlamaBridge.nativeOpMutex`).

Новое в `app`:

- `MediaCodecAudioSource` — потоковый декодер (MediaExtractor/MediaCodec),
  реализует `AudioSource`; ни один файл не грузится в память целиком.
- `WhisperCppRuntime` (`ModelRuntime`, `RuntimeKind.WHISPER_CPP`) +
  `WhisperCppSpeechModel` (`SpeechModelHandle`):
  - `transcribe()`: decoder-продюсер (свой `CoroutineScope`, `Dispatchers.IO`)
    → **bounded `Channel<ShortArray>`** (backpressure) → потребитель
    накапливает whisper-окно (~30 c, как в reference) → `nativeTranscribe`
    на каждое окно → сегменты со смещением по времени → итоговый `Transcript`.
    Первые сегменты доступны до того, как декодирован весь файл — это и
    есть основной acceptance-критерий вертикального среза.
  - `startStreaming()`: тот же `UtteranceAccumulator`/`UtteranceConfig`,
    что уже жил в `core/audio/VoiceActivity.kt` для микрофона — политика
    (relative VAD threshold, лидирующая тишина не буферизуется,
    финализация по паузе) не переизобреталась.
  - Cancellation: `requestCancel()` = Kotlin-флаг (останавливает цикл по
    окнам) + `nativeCancel` (прерывает текущий `whisper_full`). Decoder
    выключается через отмену его coroutine (`ensureActive()` в
    `MediaCodecAudioSource`'s цикле) и закрытие `Channel`.
  - Модель грузится один раз, переиспользуется через `RuntimeManager`
    (ref-counting), как и `LlamaCppRuntime`.
- `AppContainer`: `WhisperCppRuntime` теперь всегда есть в карте
  `RuntimeManager.runtimes` (была ошибка — карта строилась только с одним
  раннером на оркестратор), и `registry()` добавляет запись для
  `Capability.SPEECH_TO_TEXT`, если whisper-модель реально установлена —
  то, чего не было вообще (см. «Аудит» выше).
- `TranscribeActivity` — тестовый экран вертикального среза: file/folder
  picker (SAF, перенесено из `claude-code`'s `MainActivity`/`MediaFileUtils`),
  результат каждого файла сохраняется в `.txt` сразу после готовности.
  Доступен из меню чата. Не идёт через `Orchestrator`/pipeline — как и
  старый ad hoc mic-путь, дёргает `WhisperCppRuntime` напрямую через новый
  `WhisperFileTranscriber` (грузит модель один раз, переиспользует на весь
  batch).
- Выгрузка под memory pressure: main незадолго до этого научил
  `AppContainer.releaseMemoryUnderPressure()` освобождать простаивающие
  LLM (`releaseLocalModels()` → `RuntimeManager.evictIdle()` по всем
  оркестраторам) — но не голосовые модели. Симметрично добавлено
  `releaseWhisperEngines()`: освобождает `whisperEngine`/
  `whisperPreviewEngine` (старый ad hoc mic-путь — на этом билде
  недостижим, т.к. кнопка микрофона скрыта, но код оставлен на будущее) и
  `whisperFileTranscriber` (реально достижим через `TranscribeActivity`).
  `WhisperFileTranscriber` для этого стал полем `AppContainer`, а не
  локальным для Activity — иначе освобождать было бы нечего.
  **Важно:** `WhisperFileTranscriber`, в отличие от `WhisperCppSpeechModel`,
  не проходит через `RuntimeManager` и не защищён его refCount'ом — без
  дополнительной защиты `release()` из memory-pressure-хендлера (другая
  корутина) мог бы освободить нативный handle прямо во время идущей
  транскрипции (use-after-free). Исправлено: `release()` сначала
  запрашивает отмену и дожидается (`Job.join()`) завершения активного
  вызова, и только потом освобождает — тот же приём, что уже использует
  `LlamaTextModel.close()`.

## Изменённые/новые публичные API

- `SpeechModelHandle`: добавлен `startStreaming(language): StreamingSpeechSession`
  (обязателен для реализации — все существующие реализации обновлены).
- Новый интерфейс `StreamingSpeechSession` (`core.runtime`).
- Новый интерфейс `AudioSource` (`core.audio`).
- `WhisperBridge.nativeTranscribe` — добавлен параметр `sink: SegmentSink?`
  (без значения по умолчанию: `external fun` не поддерживает default-параметры).
- `WhisperBridge.nativeCancel` — новый метод.

## Что не перенесено и почему

- **Folder/batch (`transcribeDirectory`)** — сознательно оставлено на Phase 2.
  Задание прямо указывает: сначала один vertical slice, не переписывать всё
  сразу.
- **Живой микрофон через `WhisperCppSpeechModel.startStreaming()`** —
  `ChatActivity` продолжает использовать старый ad hoc путь
  (`WhisperEngine`/`AudioRecorder`/`WhisperTranscriber`), он не тронут и не
  регрессирует. Причина не в лени: `RuntimeManager.withModel {}` отдаёт
  модель на время одного вызова и снижает refCount сразу после возврата
  блока — а `startStreaming()` возвращает сессию, которая продолжает
  дёргать нативный хэндл в фоне уже после того, как вызвавший её `withModel`
  вернулся. Подключать реальный микрофон к этому пути без доработки
  ref-counting (сессия должна сама удерживать acquire до `finish()`/`cancel()`)
  — использование объекта после потенциального `close()`. Подробно
  задокументировано в `WhisperCppSpeechModel.close()`. Это Phase 3 по
  собственной разбивке задания.
- **`content://` URI** — `WhisperCppSpeechModel.audioSourceFor` понимает
  `file://` и голые пути/URL (тот же контракт, что уже был у
  `OpenAiRuntime.RemoteSpeechModel.audioFile()`); ни один вызывающий код в
  приложении сегодня не строит `AudioRef` с `content://` — экрана выбора
  файла нет. Добавить `Context`-резолвинг, когда появится реальный UI.
- **UI экрана «выбрать файл → транскрибировать»** — изначально не создавался
  по той же логике (Definition of Done про экран не говорит). Позже
  добавлен `TranscribeActivity` — см. отдельный раздел ниже: пользователю
  оказалось нужно реально потестить на устройстве, а не только прочитать
  код.
- **CMakeLists.txt** — не менялся: в AI он уже был на уровне (или лучше)
  reference-реализации (`-O3`/`-DNDEBUG` принудительно, `armv8.2-a+dotprod+fp16+i8mm`,
  пиннутый `whisper.cpp v1.9.3`). Единственное, чего не хватало — `flash_attn`
  на уровне `whisper_context_params`, это runtime-флаг, не CMake — добавлен в `nativeLoad`.

## Известные ограничения (честно, не спрятано в коде)

1. Один `nativeOpMutex` глобален на все whisper-модели, а не per-handle —
   при нескольких одновременно загруженных whisper-моделях (маловероятно,
   но возможно через `RuntimeManager`) инференс на разных моделях будет
   сериализован без необходимости. Тот же компромисс уже принят в
   `LlamaBridge`.
2. `requestCancel()` на `WhisperCppSpeechModel` прерывает *любой* текущий
   нативный вызов на этом handle — включая чужой, если одновременно идут
   и `transcribe()`, и `startStreaming()`-сессия. Для одного активного
   вызова за раз (текущий сценарий использования) это не проблема.
3. ~~`startStreaming()` не готов к продакшену на живом микрофоне~~ —
   актуализировано: `close()` теперь сам берёт `nativeOpMutex` перед
   `nativeFree`, так что он безопасен против гонки с сессией
   `startStreaming()` в любом случае (см. Phase 3 ниже). Остаётся
   ограничение: `WhisperCppMicSession` не заведён через `RuntimeManager`
   (тот же выбор, что и `WhisperFileTranscriber`) — RAM-бюджетом живой
   mic-сессии `RuntimeManager` не управляет, только `releaseMemoryUnderPressure`.

## Прогресс по фазам (обновлено после аудита + доработки)

- **Phase 2 — по факту сделан.** `TranscribeActivity`'s folder picker
  (`MediaFileUtils.listMediaFilesRecursively` + `WhisperFileTranscriber`)
  уже даёт: обход директории, последовательную обработку, сохранение
  каждого результата сразу после готовности, одну загруженную модель на
  весь проход. Отдельного публичного `transcribeDirectory()` API нет —
  логика инкапсулирована в Activity, этого достаточно для текущей цели
  (тестовый экран, не публичный SDK).
- **Phase 3 — инфраструктура готова, в продакшен UI не встроена.**
  Новое:
  - `MicrophoneAudioSource` (`app/whisper/`) — микрофон как `AudioSource`,
    параметры захвата перенесены из уже рабочего `AudioRecorder` (MIC,
    не VOICE_RECOGNITION; ×8 буфер; блоки ~0.25 с).
  - `WhisperCppMicSession` (`app/whisper/`) — по аналогии с
    `WhisperFileTranscriber`: грузит модель напрямую через
    `WhisperCppRuntime` (не через `RuntimeManager` — тот же выбор, что и
    `WhisperFileTranscriber`, по той же причине: `RuntimeManager` рассчитан
    на acquire-use-release в границах одного вызова, а не на
    открытую по времени сессию), качает `AudioSource` в
    `StreamingSpeechSession.acceptAudio()` на фоновой корутине.
  - `WhisperCppSpeechModel.close()` исправлен на реальную безопасность:
    теперь берёт `WhisperBridge.nativeOpMutex` перед `nativeFree` — тем
    самым ждёт любой текущий нативный вызов (batch или streaming) вместо
    того, чтобы просто полагаться на то, что вызывающий код никогда не
    попросит закрыть занятую модель. Это и была основная техническая
    причина, по которой `startStreaming()` не был безопасен для живого
    использования — теперь безопасен.
  - `TranscribeActivity` получил раздел «LIVE MIC (PHASE 3 TEST)» —
    кнопка старт/стоп, разрешение `RECORD_AUDIO`, живой транскрипт на
    экране. Не идёт через `Orchestrator`.
  - **Сознательно не тронуто:** `ChatActivity`'s собственная кнопка
    микрофона (`WhisperEngine`/`AudioRecorder`, сейчас скрыта из-за
    известных проблем с качеством/латентностью — см. комментарий в
    `activity_chat.xml`) не переведена на этот путь. Причина не техническая:
    это решение о продукте («достаточно ли теперь хорошо качество,
    учитывая выбор модели»), которое требует реального устройства и
    решения человека, а не архитектурного изменения втихую без возможности
    проверить. `WhisperCppMicSession` — новая, отдельная инфраструктура,
    которую можно подключить к `ChatActivity`, когда это решение будет
    принято.
- **Phase 4 — исследовано, вывод отрицательный для whisper.cpp конкретно.**
  whisper.cpp — full-context encoder (весь mel-спектрограм окна целиком на
  вход энкодера), а не потоково-ориентированная архитектура (Zipformer/
  Conformer с causal-чанкингом, как у настоящих streaming-движков). У
  `whisper_full` нет персистентного состояния декодирования между вызовами,
  которое позволяло бы «добавить чуть аудио → получить только новые
  токены» без пересчёта энкодера заново. Значит true token-level
  incremental streaming **не достижим на whisper.cpp путём доработки
  Kotlin/JNI-слоя** — это не то, что можно доделать поверх существующего
  `startStreaming()`, sliding-window — практический потолок для этого
  движка. Phase 4 фактически = Phase 5 (сменить движок на
  архитектурно-потоковый), не отдельный шаг.

- **Phase 5 — исследовано (Sherpa-ONNX), не реализовано: три реальных
  препятствия, не отговорки.** Собрал конкретные факты, а не общие слова:

  1. **Интеграция AAR неоднозначна.** Официальный путь — скачать
     `sherpa-onnx-<version>.aar` (~48 МБ) с GitHub Releases
     (`github.com/k2-fsa/sherpa-onnx/releases`) и подключить либо через
     Ivy-репозиторий, указывающий на паттерн GitHub Releases (стандартный
     трюк для бинарных ассетов без Maven Central), либо публикацией в
     local Maven. При этом есть открытое сообщение о том, что AGP 8+
     отклоняет голые `.aar` внутри library-модуля напрямую — этот репозиторий
     на AGP 8.7.3 (см. `whisper/build.gradle.kts`), риск реальный, но
     непроверенный без реальной сборки.
  2. **Нет проверенной маленькой модели с покрытием русского.** У
     Sherpa-ONNX streaming-модели в основном китайский/английский/японский/
     корейский/кантонский. Нашёл `alphacep/vosk-model-small-streaming-ru`
     (Zipformer2, Apache-2.0, экспортирован в ONNX как
     `csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-2025-08-16`
     на Hugging Face) — единственный найденный streaming-вариант с русским,
     но: (a) ~597 МБ — крупнее, чем "small" whisper (466 МБ) и сравнимо с
     "medium" (539 МБ), то есть выигрыша в размере над уже используемым
     whisper для этого языка нет; (b) есть открытый GitHub issue про
     лицензию конвертированной ONNX-версии (`k2-fsa/sherpa-onnx#3914`),
     не проверено, что реально разрешено использование в приложении; (c)
     нет уверенности в стабильности hosting (Hugging Face, не проверено из
     этой среды выполнения — есть сетевые ограничения, `k2-fsa.github.io`
     оказался заблокирован политикой egress прямо во время исследования).
  3. **Kotlin API уже задокументирован** (для следующего, кто будет это
     делать, не нужно исследовать заново):
     ```kotlin
     // Конфигурация
     val config = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(...), ...)
     val recognizer = OnlineRecognizer(assetManager = null, config = config)
     val stream = recognizer.createStream()
     // Цикл:
     stream.acceptWaveform(samples: FloatArray, sampleRate: Int)
     while (recognizer.isReady(stream)) recognizer.decode(stream)
     val result = recognizer.getResult(stream) // .text
     val isEndpoint = recognizer.isEndpoint(stream)
     if (isEndpoint) recognizer.reset(stream)
     // Финал:
     stream.inputFinished()
     stream.release(); recognizer.release()
     ```
     Форма (`config` объект → `createStream()` → push samples →
     `isReady`/`decode`/`getResult` в цикле) отличается от нативного
     JNI-стиля `WhisperBridge` — обёртка `SherpaSpeechModel` реализовывала
     бы `SpeechModelHandle`/`StreamingSpeechSession` так же, как
     `WhisperCppSpeechModel`, но внутренний цикл был бы другим (не одна
     блокирующая нативная функция на окно, а именно push-decode-poll).

  **Вывод:** не форсировал слепую интеграцию тремя непроверенными
  допущениями разом (сборка, лицензия, реальная польза для русского) без
  устройства и без решения человека про рост APK на десятки МБ. Это
  конкретный, выполнимый план для следующего шага — не тупик, а
  зафиксированное состояние исследования.

- **`RemoteSpeechModel`** — уже существует
  (`OpenAiRuntime.RemoteSpeechModel`), уже подключается через `registry()`
  при `RuntimeKind.REMOTE_OPENAI`. `QwenAsrSpeechModel` (отдельный,
  Qwen-специфичный движок, не через OpenAI-совместимый REST) не
  исследовался — не было сигнала, что он даёт что-то, чего не даёт уже
  подключённый `RemoteSpeechModel`.

## Ветки этой работы

- `claude/asr-pipeline-migration-5035ni` — Phase 1 (vertical slice) +
  `TranscribeActivity` (batch/Phase 2) + memory-pressure fix для голосовых
  моделей + play/copy кнопки. Собран и проверен в CI (build + smoke-test
  зелёные).
- `claude/asr-phase3-mic-streaming` — форк от предыдущей, начиная отсюда
  Phase 3 (эта секция) ведётся отдельно от уже проверенной Phase 1/2 ветки.

## Benchmark

**Не выполнялся.** В этой среде выполнения (облачный sandbox) нет
физического Android-устройства — Pixel 10 Pro, RTF, first-result latency,
RAM/CPU/battery/thermal и accuracy-замеры из задания требуют реального
железа. Инфраструктура для замеров (сам `WhisperCppSpeechModel`, сегменты
с таймстемпами) готова; сами цифры — предстоит снять на устройстве.

## Верификация в этой среде — и её пределы

**Обновление:** репозиторий уже настроен на сборку в CI (`.github/workflows/android.yml`,
триггерится на пуш в `claude/**`), с доступом к jitpack.io и Android SDK,
которых нет в этой sandbox — это и стало реальным компилятором для веток
этой сессии. CI поймал три настоящих ошибки компиляции, которые ручная
вычитка пропустила: Int/Long mismatch в тестовом фейке, отсутствующую
зависимость `kotlinx-coroutines-core` в модуле `:whisper` (там вообще не
было `dependencies {}`), не обновлённый под новую сигнатуру `nativeTranscribe`
вызов в старом `WhisperTranscriber.kt`, и пропущенный импорт
`kotlinx.coroutines.cancel`. Каждая — по отдельному коммиту, см. `git log`
этой ветки. Это подтверждает то, что было сказано ниже уже тогда: ручная
вычитка без компилятора — предположение, не факт.

Важно понимать, что реально проверено, а что нет:

- `core` (чистый Kotlin: `AudioSource`, `PcmMath`, `PcmBuffer`,
  `ModelRuntime`) — **должен** собираться и тестироваться через
  `./gradlew :core:test`, но в этой sandbox `:core` тянет `:commercial-memory`,
  которое резолвит `com.github.rmant7:Mobile_mem0` через `jitpack.io` — этот
  хост заблокирован политикой egress окружения (403 через прокси), никак не
  связано с этой задачей. `PcmMathTest`/`PcmBufferTest` написаны и логически
  проверены вручную, но **не прогнаны через реальный Gradle test runner
  здесь**. Прогнать их — первое, что стоит сделать перед merge (сработает
  в CI, если там jitpack.io доступен).
- `app`/`whisper` (Android/JNI: `MediaCodecAudioSource`, `WhisperCppRuntime`,
  `WhisperCppSpeechModel`, `whisper_jni.cpp`) — в этой sandbox нет
  `ANDROID_HOME`/NDK, `settings.gradle.kts` даже не включает эти модули
  в сборку без него. Код написан по образцу уже работающего
  `LlamaCppRuntime`/`llama_jni.cpp` и вручную вычитан, но **не
  скомпилирован и не запущен ни на эмуляторе, ни на устройстве.**

## Как проверить (для того, у кого есть Android SDK/устройство)

```bash
# 1. Чистая математика — должно быть зелёным без Android SDK вообще
./gradlew :core:test --tests "ai.localstudio.core.audio.*"

# 2. Полная сборка (нужен ANDROID_HOME или local.properties)
./gradlew :app:assembleDebug :whisper:assembleDebug

# 3. Acceptance-тест вертикального среза (см. Definition of Done в задаче):
#    - скачать whisper-tiny через существующий UI (Models)
#    - положить AMR-файл в доступное приложению хранилище
#    - вызвать WhisperCppSpeechModel.transcribe(AudioRef("file:///.../clip.amr"))
#      напрямую (пока нет UI-экрана) или через SPEECH_TO_TEXT-пайплайн
#    - убедиться, что для длинного файла (>30 c) первые TranscriptSegment
#      логируются/наблюдаются до того, как процесс декодирования файла
#      завершился — например, логированием внутри transcribeStreaming's onSegment
#    - нажать Stop/вызвать requestCancel() на середине — процесс должен
#      корректно завершиться без зависших потоков
```

Далее: [12-audio.md](12-audio.md) · [04-runtime.md](04-runtime.md)
