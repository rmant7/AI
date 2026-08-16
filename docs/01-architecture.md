# 1. Архитектура: общий обзор

## Что строим

Не «локальный чат с LLM», а **локальный AI Runtime**: среда, в которой модели —
взаимозаменяемые узлы одного пайплайна, а память и данные живут вне моделей.

Из этого следует единственное по-настоящему важное архитектурное требование:

> Ни память, ни RAG, ни UI, ни пайплайны не знают, какая модель сейчас загружена.
> Сегодня работает модель A, завтра модель B — память остаётся той же.

Всё остальное в этих документах — следствия.

## Слои

```
                        LOCAL AI STUDIO
                                │
                    ┌───────────┴───────────┐
                    │      ORCHESTRATOR     │
                    └───────────┬───────────┘
                                │
                         CAPABILITY ROUTER
                                │
       ┌────────────┬───────────┼───────────┬────────────┐
       │            │           │           │            │
     AUDIO        TEXT        VISION       VIDEO       TOOLS
       │            │           │           │            │
      ASR          LLM         VLM      Video pipeline  Functions
       │            │           │           │
       └────────────┴───────────┼───────────┘
                                │
                         CONTEXT ENGINE
                                │
                 ┌──────────────┼──────────────┐
                 │              │              │
              MEMORY           RAG          CURRENT
            (episodic,     (documents)      CONTEXT
             semantic)                     (working)
                 │              │              │
                 └──────────────┼──────────────┘
                                │
                         MODEL RUNTIME
                                │
                 ┌──────────────┼──────────────┐
                 │              │              │
              llama.cpp      MediaPipe        MLC / ONNX
                 │              │              │
               GGUF          .task          прочие форматы
```

Границы между слоями заданы интерфейсами в модуле `core`:

| Слой | Контракт | Файл |
|---|---|---|
| Capability | `Capability` | `core/src/main/kotlin/ai/localstudio/core/capability/Capability.kt` |
| Реестр моделей | `ModelDescriptor`, `ModelRegistry` | `core/src/main/kotlin/ai/localstudio/core/registry/` |
| Аудио | `UtteranceAccumulator`, `AudioAnalysis` | `core/src/main/kotlin/ai/localstudio/core/audio/VoiceActivity.kt` |
| Подбор под устройство | `DeviceProfile`, `SuitabilityScorer` | `core/src/main/kotlin/ai/localstudio/core/registry/SuitabilityScorer.kt` |
| Runtime | `ModelRuntime`, `RuntimeManager` | `core/src/main/kotlin/ai/localstudio/core/runtime/` |
| Память | `MemoryProvider` | `core/src/main/kotlin/ai/localstudio/core/memory/MemoryProvider.kt` |
| Знания (RAG) | `KnowledgeProvider` | `core/src/main/kotlin/ai/localstudio/core/knowledge/KnowledgeProvider.kt` |
| Сборка контекста | `ContextEngine` | `core/src/main/kotlin/ai/localstudio/core/context/ContextEngine.kt` |
| Маршрутизация | `CapabilityRouter` | `core/src/main/kotlin/ai/localstudio/core/router/CapabilityRouter.kt` |
| Пайплайны | `PipelineSpec`, `PipelineEngine` | `core/src/main/kotlin/ai/localstudio/core/pipeline/` |
| Оркестратор | `Orchestrator`, `PipelineBuilder`, `ModelSelector` | `core/src/main/kotlin/ai/localstudio/core/engine/` |

## Три опоры

**1. Capability вместо имени модели.** Система спрашивает не «какая модель
выбрана», а «какая модель лучше всех решает эту задачу здесь и сейчас».
См. [02-capabilities.md](02-capabilities.md).

**2. Модель ≠ runtime.** `Qwen` — модель, `llama.cpp` — способ её исполнения.
Одна модель может иметь несколько bindings с разными требованиями к памяти и
ускорителям. См. [03-model-registry.md](03-model-registry.md) и
[04-runtime.md](04-runtime.md).

**3. Контекст собирается снаружи модели.** Модель никогда не «помнит» — ей
передают собранную память. Поэтому замена модели не стирает историю.
См. [06-context-engine.md](06-context-engine.md).

## Как запрос проходит систему

Голос:

```
MIC → VAD → ASR → Transcript
                      ↓
             Memory retrieval + RAG
                      ↓
                Context Engine
                      ↓
           Best local LLM (по capability)
                      ↓
                  Response
                      ↓
                Memory update
```

Изображение:

```
CAMERA → VLM → описание + OCR → Context Engine → LLM → Response
```

Видео:

```
VIDEO ─┬─ audio → ASR ────┐
       └─ frames → VLM ───┤
                          ├─ temporal context → Context Engine → LLM
                      OCR ┘
```

Существенно, что LLM во всех трёх случаях одна и та же и ничего не знает о
происхождении текста: разница только в том, какие фрагменты положил в контекст
Context Engine.

## Что уже есть в репозитории

Модуль `core` — это не прототип приложения, а исполняемая спецификация: контракты
слоёв плюс та логика, которая должна быть одинаковой на телефоне и на десктопе
(подбор модели под устройство, менеджер памяти, сборка контекста, валидация и
исполнение пайплайнов). Всё покрыто тестами и собирается без Android SDK, что
позволяет отлаживать архитектуру до появления UI.

Дальше: [02-capabilities.md](02-capabilities.md) ·
[13-orchestrator.md](13-orchestrator.md) ·
[10-stack-and-roadmap.md](10-stack-and-roadmap.md) · [12-audio.md](12-audio.md)
