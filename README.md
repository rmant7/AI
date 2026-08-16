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
docs/           архитектура (13 документов, читать по порядку)
core/           ядро: контракты, оркестратор, память, RAG, пайплайны
openai/         runtime поверх OpenAI-совместимого API (Ollama, llama-server)
app/            Android-приложение: чат, модели, настройки
pipelines/      готовые пайплайны в JSON
registry/       пример каталога моделей
```

## APK

Готовый debug-APK собирается в CI и публикуется как release-ассет:
**[последняя сборка](https://github.com/rmant7/AI/releases)**.

Приложение работает сразу после установки: без настроек включён встроенный
демонстрационный runtime — запрос всё равно проходит роутер, сборку пайплайна,
выбор модели, память и Context Engine, и в ответе видно, из чего собрался
контекст. Чтобы отвечала настоящая модель, укажите в настройках адрес
OpenAI-совместимого сервера (Ollama: `http://<адрес>:11434/v1`).

Экран Models прогоняет реальный скорер против реального устройства: показывает
RAM, бюджет на модель и вердикт по каждой модели каталога, включая причину, по
которой она здесь не запустится.

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
| [13 — Orchestrator](docs/13-orchestrator.md) | путь запроса целиком, выбор модели, что видно наружу |

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
| `engine/Orchestrator` | запрос → маршрут → граф → исполнение → ответ с трассой |
| `engine/PipelineBuilder` | маршрут роутера превращается в граф: чат идёт тем же путём, что и пайплайн |
| `engine/ModelSelector` | какая установленная модель закрывает capability на этом устройстве |
| `engine/NodeExecutors` | стадии графа поверх runtime, памяти, RAG и контекста |
| `knowledge/LocalKnowledgeProvider` | parse → chunk → embed → cosine → rerank |
| `memory/InMemoryMemoryProvider` | working / episodic / semantic без внешних зависимостей |

Android SDK не нужен — это обычный JVM-модуль, что позволяет отлаживать
архитектуру до появления UI.

## Модуль openai

`OpenAiRuntime` — реализация `ModelRuntime` поверх OpenAI-совместимого API
(Ollama, llama-server): генерация со стримингом, эмбеддинги, транскрипция
(multipart), отмена, разбор ошибок сервера. Зависимостей, кроме JDK-клиента и
kotlinx-serialization, нет.

Это режим разработки из [docs/10](docs/10-stack-and-roadmap.md): те же роутер,
пайплайны и контекст, что пойдут на телефон, отлаживаются на десктопном железе.
На устройстве меняется только зарегистрированный runtime.

```kotlin
val runtime = OpenAiRuntime(OpenAiConfig(baseUrl = "http://localhost:11434/v1"))
val manager = RuntimeManager(budgetBytes = 1_000_000, runtimes = mapOf(REMOTE_OPENAI to runtime))
val answer = orchestrator.handle(UserRequest(conversationId = "dev", text = "привет"))
```

## Сборка и тесты

```bash
./gradlew test
```

137 тестов: подбор моделей под устройство, вытеснение из памяти, сборка
контекста, маршрутизация, построение и исполнение графов, чанкинг и векторный
поиск, память, VAD и жизненный цикл реплики, выбор артефакта модели.
`RepositoryAssetsTest` проверяет, что JSON в `pipelines/` и `registry/` не
разошёлся с кодом, а `:openai` гоняет runtime против настоящего HTTP-сервера,
включая сквозной прогон «голос → расшифровка → память → ответ».

## Откуда взяты числа

Параметры аудио, флаги нативной сборки, тиринг моделей по RAM и правила выбора
артефакта — не проектные допущения, а результаты работающей реализации
whisper.cpp на Android (`WhisperTranscriber`, ветка
`claude/android-whisper-transcription-3tuggu` в `rmant7/claude-code`).
Подробности и причины — в [docs/12](docs/12-audio.md),
[docs/04](docs/04-runtime.md) и [docs/03](docs/03-model-registry.md).

## Статус

Этапы 0 и 1 из плана в [docs/10](docs/10-stack-and-roadmap.md): зафиксированы
контракты, собрано ядро и оркестратор, запрос проходит систему целиком против
OpenAI-совместимого эндпоинта. Дальше — llama.cpp/ASR на устройстве, Android UI
и постоянные хранилища.
