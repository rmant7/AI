# 8. Pipeline Engine

Код: `core/src/main/kotlin/ai/localstudio/core/pipeline/`
Примеры: `pipelines/*.json`

## Что это даёт

Чат — частный случай пайплайна. Именно наличие редактируемых цепочек превращает
приложение из чат-клиента в локальную AI workflow-платформу:

```
Voice     → Think
Voice     → Translate
Image     → Analyze
Video     → Summarize
Document  → RAG → Answer
Voice     → RAG → LLM
Voice     → LLM → Action
```

## Формат

Собственная JSON-схема — узлы и рёбра. Готовый workflow-движок на телефон не
тянем: он тяжелее самой задачи.

```json
{
  "id": "voice_rag",
  "name": "Voice → Memory → RAG → LLM",
  "nodes": [
    { "id": "mic",     "type": "microphone" },
    { "id": "vad",     "type": "vad", "params": { "min_silence_ms": "500" } },
    { "id": "stt",     "type": "speech_to_text" },
    { "id": "memory",  "type": "memory_search" },
    { "id": "rag",     "type": "knowledge_search" },
    { "id": "context", "type": "context_build" },
    { "id": "llm",     "type": "text_generation" },
    { "id": "save",    "type": "memory_update" },
    { "id": "out",     "type": "response" }
  ],
  "edges": [
    { "from": "mic", "to": "vad" },
    { "from": "vad", "to": "stt" },
    { "from": "stt", "to": "memory" },
    { "from": "stt", "to": "rag" },
    { "from": "stt", "to": "context" },
    { "from": "memory", "to": "context" },
    { "from": "rag", "to": "context" },
    { "from": "context", "to": "llm" },
    { "from": "llm", "to": "save" },
    { "from": "llm", "to": "out" }
  ]
}
```

Узел объявляет **тип стадии**, а не модель. `speech_to_text` требует
`Capability.SPEECH_TO_TEXT` — какая модель её закроет, решается в момент
запуска. Пайплайн, сохранённый год назад, переживает полную смену набора
моделей.

## Типы узлов

```
источники   microphone, audio_input, camera, image_input,
            video_input, document_input, text_input

обработка   vad, speech_to_text, diarization, frame_extract,
            vision_analyze, ocr

контекст    memory_search, knowledge_search, context_build

вывод       text_generation, memory_update, response
```

`NodeType.requires` связывает стадию с capability, `NodeType.isSource` — с
входом. `PipelineSpec.requiredCapabilities()` даёт полный список требований
графа: этого достаточно, чтобы до запуска сказать «для этого пайплайна не
установлена модель с capability `ocr`».

## Валидация

`PipelineValidator` проверяет структуру до сохранения и до запуска:

```
DUPLICATE_NODE_ID       повторяющийся id узла
UNKNOWN_EDGE_ENDPOINT   ребро в несуществующий узел
SELF_LOOP               ребро узла в самого себя
CYCLE                   граф не ациклический
NO_SOURCE_NODE          нет входа
NO_TERMINAL_NODE        нет response
UNREACHABLE_NODE        узел, до которого не доходит поток
SOURCE_WITH_INPUT       во входной узел ведёт ребро
EMPTY_PIPELINE          пустой граф
```

Висячее ребро сообщается именно как висячее и не выдаёт себя за цикл — иначе
самая частая ошибка редактирования диагностируется самым непонятным сообщением.

## Исполнение

`PipelineEngine` идёт по топологическому порядку (алгоритм Кана), передавая
`NodeValue` по рёбрам, и сохраняет выход каждого узла плюс трассу с временами.

Движок ничего не знает о моделях, памяти и RAG — каждая стадия это
`NodeExecutor`, переданный снаружи. Один и тот же граф исполняется:

- на телефоне — против реальных runtime;
- в разработке — против OpenAI-совместимого эндпоинта;
- в тестах — против фейков (`PipelineEngineTest`).

Многовходовые узлы получают выходы всех входящих рёбер: `context_build` в
примере выше собирает транскрипт, память и RAG в один список фрагментов и
передаёт их в [Context Engine](06-context-engine.md).

## Чат — это тоже пайплайн

Обычный запрос не идёт мимо этого механизма. `PipelineBuilder.fromRoute`
превращает [маршрут](07-router.md) в граф, и дальше он проходит тот же
валидатор и тот же исполнитель, что и сохранённый пользователем пайплайн:

```
RequestSignals → Router → RoutePlan → PipelineBuilder → PipelineSpec
                                                            ↓
                                              Validator → PipelineEngine
```

Форма графа повторяет `voice_rag.json`: вход обрабатывается цепочкой
(VAD → ASR, кадры → VLM), retrieval-узлы висят параллельно на результате этой
обработки, и всё сходится в `context_build`.

Смысл в том, что второго пути исполнения не существует. Если бы чат ходил в
модель напрямую, а пайплайны — через движок, то любое поведение (бюджет
контекста, выбор модели, запись в память) пришлось бы чинить дважды и оно бы
разъезжалось.

## Готовые пайплайны в репозитории

| Файл | Цепочка |
|---|---|
| `pipelines/voice_rag.json` | mic → vad → stt → (memory + rag) → context → llm → (save, out) |
| `pipelines/photo_analyze.json` | camera → (vision + ocr) → context → llm → out |
| `pipelines/video_summary.json` | video → (frames → vision/ocr, audio → stt → diarize) → context → llm → out |
| `pipelines/meeting_notes.json` | audio → stt → diarize → context → llm → memory → out |

Все четыре разбираются и валидируются тестом `RepositoryAssetsTest`, то есть
примеры не могут разойтись с кодом.

Дальше: [09-storage.md](09-storage.md)
