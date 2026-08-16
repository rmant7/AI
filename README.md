# Local AI Studio

Локальный AI Runtime для Android: модели — взаимозаменяемые узлы одного
пайплайна, память и данные живут вне моделей.

> Сегодня работает модель A, завтра модель B. Память остаётся той же.

Это не чат с локальной LLM. Голос, изображение, видео и документы входят в одну
и ту же систему; capability-роутер подбирает модель под задачу и под конкретное
устройство; контекст собирается снаружи модели; цепочки обработки редактируются
пользователем.

## Состав репозитория

```
docs/           архитектура (12 документов, читать по порядку)
core/           контракты и логика ядра на Kotlin, с тестами
pipelines/      готовые пайплайны в JSON
registry/       пример каталога моделей
```

## Документация

| | |
|---|---|
| [01 — Архитектура](docs/01-architecture.md) | слои, принципы, путь запроса |
| [02 — Capabilities](docs/02-capabilities.md) | почему capability, а не имя модели |
| [03 — Model Registry](docs/03-model-registry.md) | модель ≠ runtime, подбор под устройство, discovery |
| [04 — Runtime](docs/04-runtime.md) | абстракция движков, управление памятью |
| [05 — Память и знания](docs/05-memory-knowledge.md) | четыре типа памяти, границы хранилищ |
| [06 — Context Engine](docs/06-context-engine.md) | сборка контекста, бюджет, приоритеты |
| [07 — Router](docs/07-router.md) | запрос → capability |
| [08 — Pipelines](docs/08-pipelines.md) | формат, валидация, исполнение |
| [09 — Хранение](docs/09-storage.md) | раскладка файлов на устройстве |
| [10 — Стек и план](docs/10-stack-and-roadmap.md) | что берём готовым, MVP, риски |
| [11 — UI](docs/11-ui.md) | экраны |
| [12 — Audio Engine](docs/12-audio.md) | захват, VAD, жизненный цикл реплики, параметры whisper |

## Модуль core

`core` — исполняемая спецификация архитектуры: контракты слоёв плюс логика,
одинаковая на телефоне и на десктопе.

| Компонент | Что делает |
|---|---|
| `capability/Capability` | словарь возможностей: `speech_to_text`, `reasoning`, `ocr`, `embedding`, … |
| `registry/ModelDescriptor` | модель, её capabilities и bindings под разные runtime |
| `registry/SuitabilityScorer` | топ-N моделей под конкретное устройство: жёсткие фильтры + оценка |
| `registry/ModelRegistry` | installed/available, merge каталога, diff обновлений |
| `registry/ArtifactResolver` | выбор конкретного файла в репозитории модели: приоритет квантования, отсев многотомных |
| `audio/UtteranceAccumulator` | VAD по измеренному шумовому порогу, накопление реплики, момент финализации |
| `runtime/ModelRuntime` | абстракция llama.cpp / MediaPipe / MLC / ONNX / OpenAI-совместимого API |
| `runtime/RuntimeManager` | что держать в RAM: переиспользование, LRU-вытеснение, защита используемых |
| `memory/MemoryProvider` | working / episodic / semantic память за интерфейсом |
| `knowledge/KnowledgeProvider` | документы: ingest, поиск, удаление |
| `context/ContextEngine` | сборка промпта под бюджет токенов с приоритетами и отчётом об отброшенном |
| `router/CapabilityRouter` | запрос → список capability и стадий |
| `pipeline/*` | JSON-схема графа, валидатор, исполнитель |

Android SDK не нужен — это обычный JVM-модуль, что позволяет отлаживать
архитектуру до появления UI.

## Сборка и тесты

```bash
./gradlew :core:test
```

77 тестов: подбор моделей под устройство, вытеснение из памяти, сборка
контекста, маршрутизация, валидация и исполнение пайплайнов, VAD и жизненный
цикл реплики, выбор артефакта модели. Тест `RepositoryAssetsTest` проверяет,
что JSON в `pipelines/` и `registry/` не разошёлся с кодом.

## Откуда взяты числа

Параметры аудио, флаги нативной сборки, тиринг моделей по RAM и правила выбора
артефакта — не проектные допущения, а результаты работающей реализации
whisper.cpp на Android (`WhisperTranscriber`, ветка
`claude/android-whisper-transcription-3tuggu` в `rmant7/claude-code`).
Подробности и причины — в [docs/12](docs/12-audio.md),
[docs/04](docs/04-runtime.md) и [docs/03](docs/03-model-registry.md).

## Статус

Этап 0 из плана в [docs/10](docs/10-stack-and-roadmap.md): зафиксированы
контракты и ядро логики. Реализации runtime, Android UI и хранилищ — следующие
этапы.
