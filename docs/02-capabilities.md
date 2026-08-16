# 2. Capability Registry

## Проблема

Если UI, пайплайны и память ссылаются на модели по именам («Qwen», «Whisper»),
то каждая новая модель — это правка во всех этих местах. Через полгода в коде
живёт десяток имён, половина из которых уже неактуальна.

## Решение

Модель описывается не именем, а множеством capability — что она умеет:

```
speech_to_text        text_generation      embedding
speaker_diarization   reasoning            reranking
vision                coding
ocr                   translation
image_understanding
video_understanding
```

Список — `Capability` (`core/src/main/kotlin/ai/localstudio/core/capability/Capability.kt`).

```
Модель "text-large-8b"
 ├── text_generation
 ├── reasoning
 ├── coding
 └── translation

Модель "asr-multilingual-small"
 └── speech_to_text

Модель "embedding-multilingual-small"
 └── embedding
```

## Кто чем пользуется

```
Router          → «нужен speech_to_text» → Registry → ranked models → Runtime
Pipeline node   → NodeType.SPEECH_TO_TEXT.requires == Capability.SPEECH_TO_TEXT
UI              → показывает модели, сгруппированные по capability
```

Узлы пайплайна объявляют требуемую capability декларативно
(`NodeType.requires`), поэтому сохранённый сегодня пайплайн продолжит работать
после полной замены набора моделей — `PipelineSpec.requiredCapabilities()`
проверяется при запуске, а конкретную модель подбирает
[SuitabilityScorer](03-model-registry.md).

## Правило именования

Capability описывает **задачу**, а не архитектуру модели и не модальность:

- ✅ `ocr` — «извлечь текст с изображения»
- ❌ `vlm` — это класс моделей, а не задача
- ✅ `reranking`
- ❌ `cross_encoder` — деталь реализации

Проверка: если при появлении новой модели хочется добавить capability с именем
этой модели или её архитектуры — capability выбрана неверно.

## Почему embedding и reranking здесь же

Соблазн считать эмбеддинги «частью RAG» и спрятать их внутрь vector store.
Тогда embedding-модель окажется единственной, которую нельзя обновить или
заменить через общий Model Manager, а размерность вектора — молчаливым
допущением всей базы. Эмбеддер и реранкер — такие же модели с такими же
требованиями к RAM и такой же процедурой обновления; смена эмбеддера означает
переиндексацию, и это должно быть видимой операцией, а не побочным эффектом.

Дальше: [03-model-registry.md](03-model-registry.md)
