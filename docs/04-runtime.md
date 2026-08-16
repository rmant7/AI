# 4. Model Abstraction Layer и Runtime Manager

Код: `core/src/main/kotlin/ai/localstudio/core/runtime/`

## Абстракция

```
                      Model API
                          │
        ┌─────────────┬───┴────┬─────────────┐
        │             │        │             │
    llama.cpp     MediaPipe   MLC       ONNX Runtime
        │             │        │             │
      GGUF          .task    свои         .onnx
```

```kotlin
interface ModelRuntime {
    val kind: RuntimeKind
    fun canRun(model: ModelDescriptor, binding: RuntimeBinding): Boolean
    suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel
}
```

Загруженная модель отдаётся наружу как capability-специфичный handle:

```kotlin
TextModelHandle      → generate(GenerationRequest): Flow<String>
SpeechModelHandle    → transcribe(AudioRef, language): Transcript
VisionModelHandle    → analyze(ImageRef, prompt): VisionResult
EmbeddingModelHandle → embed(List<String>): List<FloatArray>
```

Добавление нового движка = одна реализация `ModelRuntime` + `RuntimeBinding` в
каталоге. Router, Context Engine, пайплайны и UI не меняются. Это же даёт
режим разработки: `REMOTE_OPENAI` (Ollama, llama-server) — такой же runtime,
только модель исполняется на десктопе.

## Почему не один runtime

- **llama.cpp** — основная масса GGUF, максимальный выбор моделей;
- **MediaPipe / LiteRT** — модели, оптимизированные под Android, доступ к GPU/NPU;
- **MLC** — отдельный путь оптимизации для части моделей;
- **whisper.cpp / sherpa-onnx** — ASR;
- **ONNX Runtime** — эмбеддеры и реранкеры.

Привязка ко одному движку означает потолок по каталогу моделей либо по
производительности. Стоимость абстракции — один интерфейс.

## Runtime Manager: кто живёт в памяти

Главное ограничение телефона: ASR + LLM + VLM конкурируют за одну и ту же RAM.

```
Мало памяти                       Достаточно памяти
────────────                      ─────────────────
Load ASR                          ASR    ─────┐
transcription                     LLM    ─────┤── все резидентны
Unload ASR                        VLM    ─────┘
Load LLM
generation
Unload LLM
```

Обе стратегии — один и тот же код:

```kotlin
runtimeManager.withModel(model, binding) { handle -> … }
```

Правила `RuntimeManager`:

1. Модель загружается при первом обращении и **остаётся резидентной** после
   освобождения — следующий вызов переиспользует её без перезагрузки.
2. При нехватке бюджета вытесняется наименее давно использованная **свободная**
   модель (refCount == 0).
3. Модель, которая прямо сейчас используется, не вытесняется никогда. Если
   бюджет не сходится без неё — `InsufficientMemoryException`, а не thrashing.
4. Модель крупнее всего бюджета отвергается сразу, ничего не выгружая.
5. `evictIdle()` — точка для `onTrimMemory()` от Android.

Поведение зафиксировано тестами в `RuntimeManagerTest`.

## Бюджет

Бюджет `RuntimeManager` — не то же самое, что `DeviceProfile.usableRamBytes`:
второй ограничивает *одну* модель при подборе, первый — *сумму* резидентных.
Разумная отправная точка — тот же `usableRamBytes`, но его стоит уменьшать,
когда приложение уходит в фон, и пересчитывать после `onTrimMemory`.

## Чего здесь нет и не будет

Собственного inference-движка. Это отдельный многолетний проект с сомнительной
отдачей: выигрыш архитектуры — в слое над движками, а не в очередной их копии.

Дальше: [05-memory-knowledge.md](05-memory-knowledge.md)
