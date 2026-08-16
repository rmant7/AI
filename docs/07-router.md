# 7. Capability Router

Код: `core/src/main/kotlin/ai/localstudio/core/router/CapabilityRouter.kt`

## Задача

Превратить запрос в список capability, а не в имя модели.

```
"Что здесь написано?"        + фото   → vision, image_understanding, text_generation
"Расшифруй это"              + аудио  → speech_to_text, text_generation
"Проанализируй этот код"             → coding, text_generation
"Посмотри видео"             + видео  → speech_to_text, video_understanding, ocr, …
"Что мы решили вчера?"               → memory_search + text_generation
"Найди в моих документах…"   + docs   → embedding, reranking, text_generation
```

Дальше `SuitabilityScorer` выбирает под каждую capability конкретную модель для
конкретного устройства, а `RuntimeManager` решает, что держать в памяти.

## Порядок разбора

```
video?     → frame_extract + speech_to_text + vision_analyze
audio?     → speech_to_text
image?     → vision_analyze
отсылка к прошлому? → memory_search
документы в области видимости? → knowledge_search
намерение (код / перевод / анализ) → coding | translation | reasoning
всегда     → context_build → text_generation → response
```

Модальности разбираются первыми, потому что они однозначны: наличие картинки —
факт, а не догадка. Текстовые правила добавляют уточняющую capability и никогда
не отменяют модальные.

## Сначала правила, потом модель

Router намеренно rule-based:

- детерминирован — один и тот же запрос всегда идёт одним маршрутом;
- ничего не стоит — не требует загрузки ещё одной модели ради выбора модели;
- объясним — `RoutePlan.explanation` содержит причину каждого решения, и её
  можно показать в UI.

Маленький локальный классификатор заменит ключевые слова позже, не меняя
контракт: наружу по-прежнему отдаётся `RoutePlan`. Начинать с модели-роутера
значило бы платить загрузкой модели за каждый запрос ещё до того, как ясно,
какие маршруты вообще встречаются на практике.

## Ограничение подхода

Ключевые слова — грубый инструмент: «объясни этот баг» уйдёт в `coding` из-за
слова «баг», хотя пользователь мог иметь в виду баг в процессе, а не в коде.
Это осознанный компромисс: цена ошибки — выбор чуть менее подходящей модели, а
не неправильный ответ, поскольку итоговый контекст собирается одинаково.

Правила вынесены в конструктор (`codingKeywords`, `translationKeywords`,
`reasoningKeywords`, `recallKeywords`), поэтому набор настраивается и
локализуется без правки логики.

Дальше: [08-pipelines.md](08-pipelines.md)
