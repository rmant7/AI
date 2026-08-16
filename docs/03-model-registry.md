# 3. Model Registry и подбор под устройство

Код: `core/src/main/kotlin/ai/localstudio/core/registry/`

## Модель ≠ runtime

```
ModelDescriptor
 ├── id, family, version
 ├── parameterCount, quantization, contextLength
 ├── languages
 ├── capabilities        ← что умеет
 ├── license, sourceUrl
 ├── benchmarks          ← reasoning / coding / vision / asrAccuracy / general
 └── bindings[]          ← как исполнять
       ├── runtime            (llama_cpp | mediapipe | mlc | onnx_runtime | …)
       ├── artifact           (.gguf | .task | …)
       ├── fileSizeBytes
       ├── requiredRamBytes
       ├── requiresGpu / requiresNpu / minAndroidApi
       └── referenceTokensPerSecond
```

Одна и та же модель может иметь binding под llama.cpp (GGUF, CPU) и под
MediaPipe (`.task`, GPU) с разными требованиями. Скорер выбирает лучший binding
для конкретного устройства сам — приложение об этом не думает.

Все бенчмарки нормализованы к шкале 0..100, «больше — лучше», включая
`asrAccuracy` (хранится как `100 − WER%`), чтобы ранжирование не зависело от
направления метрики.

## Device Profile

«Пять лучших моделей» — величина, не имеющая смысла без устройства.

```
DeviceProfile
 ├── totalRamBytes / availableRamBytes
 ├── availableStorageBytes
 ├── cpuCores, androidApiLevel
 ├── supportedRuntimes
 ├── hasGpuDelegate, hasNpu
 └── performanceIndex     ← 1.0 = эталонное устройство
```

`usableRamBytes = availableRamBytes × 0.6`. Оставшееся — ОС, процесс UI, всё
прочее резидентное. Попытка занять последний свободный байт на Android
заканчивается не медленной работой, а убитым процессом.

## Формула пригодности

`SuitabilityScorer` работает в два шага.

**Жёсткие фильтры** (модель не «хуже» — она невозможна):

```
capability не поддерживается      → CAPABILITY_NOT_SUPPORTED
нет binding под доступный runtime → NO_SUPPORTED_RUNTIME
requiredRam > usableRam           → NOT_ENOUGH_RAM
fileSize > свободного места       → NOT_ENOUGH_STORAGE   (только для неустановленных)
требуется GPU/NPU, которого нет   → GPU_REQUIRED / NPU_REQUIRED
minAndroidApi выше системного     → ANDROID_API_TOO_LOW
```

Причины несовместимости возвращаются наружу, а не отбрасываются: в UI лучше
показать «не хватает 1.2 ГБ RAM», чем молча спрятать модель.

**Оценка выживших** — взвешенное геометрическое среднее:

```
quality = benchmark(capability) / 100          вес 0.5
speed   = min(1, refTps × perfIndex / 25)      вес 0.3
memory  = 1 − requiredRam / usableRam          вес 0.2

score   = exp( Σ wᵢ · ln xᵢ / Σ wᵢ )
```

Геометрическое среднее выбрано осознанно: у арифметического один почти нулевой
фактор компенсируется двумя хорошими, и модель, съедающая всю память, всплывает
наверх за счёт бенчмарков. Здесь она тонет.

`memory` — это запас, а не факт помещаемости (помещаемость уже проверена
фильтром). Модель, занявшая весь бюджет, не оставляет места ни ASR, ни
эмбеддеру, ни VLM — то есть ломает пайплайн, а не только себя. Такое право нужно
заслужить качеством.

Неизмеренные модели получают нейтральные значения (`quality = 0.6`,
`speed = 0.5`) — не поощряются и не хоронятся.

## Что это даёт

```
TEXT / LOCAL / устройство A (10 ГБ свободно)
1. text-small-4b     qual 78  speed 22 t/s  RAM 3.4 ГБ
2. text-large-8b     qual 88  speed 11 t/s  RAM 5.8 ГБ
3. vision-4b         qual 70  speed 14 t/s  RAM 3.9 ГБ

то же самое на устройстве B (4 ГБ свободно)
1. text-small-4b
   (text-large-8b и vision-4b отфильтрованы: NOT_ENOUGH_RAM)
```

Один каталог, разные списки — потому что ранжирование зависит от устройства, а
не от лидерборда.

## Discovery и обновления

```
Internet (например, HF API)
        ↓
   ModelCatalog (JSON)
        ↓
   registry.merge(catalog)  ──→ список новых моделей
        ↓
   registry.diff(capability) ──→ кандидаты на замену установленных
        ↓
        Notification  →  пользователь решает
```

Два правила, оба реализованы в `ModelRegistry`:

1. **Каталог обновляется автоматически, модели — никогда.** Иначе через полгода
   приложение занимает сотни гигабайт.
2. **Merge не может ничего удалить или деинсталлировать.** Установленная запись
   сохраняет состояние и путь; обновляются только метаданные. Это покрыто
   тестом `a catalog refresh cannot uninstall a model`.

`diff` сравнивает версии по числовым сегментам, поэтому `3.10` новее `3.9` —
строковое сравнение здесь даёт обратный и неверный результат.

## Версии, а не замены

```
Установлено:  text-large 8B v1
Появилось:    text-large 8B v2

Рекомендация: KEEP BOTH
```

`ModelUpdate` возвращает дельты (`qualityDelta`, `speedDelta`, `ramDelta`), а не
вердикт. Новая версия не обязательно лучше для конкретной задачи: одна модель
может выигрывать в reasoning и проигрывать в скорости, и обе имеют право
остаться. Решение о выборе на каждый запрос принимает скорер, а не факт
установки.

Дальше: [04-runtime.md](04-runtime.md)
