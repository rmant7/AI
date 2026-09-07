package ai.localstudio.app.models

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.registry.RuntimeKind

/**
 * A model this app knows how to fetch and run on the device.
 *
 * [repoIds] is a list, not a single repository, for two reasons. The official
 * repositories of several families — Gemma among them — are gated: they answer
 * a plain download with 401/403 until a licence is accepted and a token is
 * attached, which is exactly how a download fails for no visible reason.
 * Community re-uploads of the same weights are not gated, so they are tried
 * first and the official repository last. The second reason is ordinary
 * availability: a repository can be renamed or restructured at any time.
 *
 * The list carries repositories, never file names: the concrete artifact is
 * resolved at download time (see [HuggingFaceResolver]), by [extension] —
 * `.gguf` for [RuntimeKind.LLAMA_CPP], `.litertlm` for [RuntimeKind.LITERT].
 */
data class LocalModelSeed(
    val id: String,
    val title: String,
    val repoIds: List<String>,
    val paramsLabel: String,
    val note: String,
    val approxSizeBytes: Long,
    val capabilities: Set<Capability> = setOf(Capability.TEXT_GENERATION, Capability.REASONING),
    val contextTokens: Int = 4096,
    val runtime: RuntimeKind = RuntimeKind.LLAMA_CPP,
) {
    val extension: String get() = if (runtime == RuntimeKind.LITERT) ".litertlm" else ".gguf"
}

object LocalModels {

    val SEEDS = listOf(
        // Gemma 4 (released April 2026) listed first — the newest, and the
        // one repeatedly asked for by name. Gemma 3 stays below as a
        // known-good fallback rather than being removed: this repo's GGUF
        // quants are new enough that a still-shifting file layout on the
        // community mirrors is a real possibility this app cannot verify
        // ahead of time, and "ни один источник не подошёл" for every Gemma
        // entry would be strictly worse than keeping the proven version
        // available too.
        LocalModelSeed(
            id = "gemma-4-e4b-it-q4",
            title = "Gemma 4 E4B Instruct",
            repoIds = listOf(
                "unsloth/gemma-4-E4B-it-GGUF",
            ),
            paramsLabel = "E4B · Q4",
            note = "Новейшая Gemma; сопоставима по размеру с Gemma 3 4B.",
            approxSizeBytes = 2_600_000_000,
        ),
        LocalModelSeed(
            id = "gemma-4-12b-it-q4",
            title = "Gemma 4 12B Instruct",
            repoIds = listOf(
                "unsloth/gemma-4-12B-it-GGUF",
                "bartowski/gemma-4-12B-it-GGUF",
                "unsloth/gemma-4-12B-it-qat-GGUF",
            ),
            paramsLabel = "12B · Q4",
            note = "Новейшая Gemma среднего размера.",
            approxSizeBytes = 7_200_000_000,
        ),
        LocalModelSeed(
            id = "gemma-4-26b-a4b-it-q4",
            title = "Gemma 4 26B-A4B Instruct",
            repoIds = listOf(
                "unsloth/gemma-4-26B-A4B-it-GGUF",
                "unsloth/gemma-4-26B-A4B-it-qat-GGUF",
            ),
            paramsLabel = "26B (MoE, ~4B активных) · Q4",
            note = "MoE-модель: качество крупной модели при инференсе на уровне ~4B активных параметров.",
            approxSizeBytes = 15_600_000_000,
        ),
        LocalModelSeed(
            id = "gemma-4-31b-it-q4",
            title = "Gemma 4 31B Instruct",
            repoIds = listOf(
                "unsloth/gemma-4-31B-it-GGUF",
            ),
            paramsLabel = "31B · Q4",
            note = "Самая большая новая Gemma. Нужно много памяти и терпение к скорости.",
            approxSizeBytes = 18_600_000_000,
        ),
        LocalModelSeed(
            id = "gemma-3-1b-it-q4",
            title = "Gemma 3 1B Instruct",
            repoIds = listOf(
                "unsloth/gemma-3-1b-it-GGUF",
                "ggml-org/gemma-3-1b-it-GGUF",
                "google/gemma-3-1b-it-qat-q4_0-gguf",
            ),
            paramsLabel = "1B · Q4",
            note = "Маленькая и быстрая: хороша, чтобы проверить, что локальный запуск работает.",
            approxSizeBytes = 800_000_000,
        ),
        LocalModelSeed(
            id = "gemma-3-4b-it-q4",
            title = "Gemma 3 4B Instruct",
            repoIds = listOf(
                "unsloth/gemma-3-4b-it-GGUF",
                "ggml-org/gemma-3-4b-it-GGUF",
                "bartowski/google_gemma-3-4b-it-GGUF",
                "google/gemma-3-4b-it-qat-q4_0-gguf",
            ),
            paramsLabel = "4B · Q4",
            note = "Баланс качества и скорости.",
            approxSizeBytes = 2_700_000_000,
        ),
        LocalModelSeed(
            id = "gemma-3-12b-it-q4",
            title = "Gemma 3 12B Instruct",
            repoIds = listOf(
                "unsloth/gemma-3-12b-it-GGUF",
                "bartowski/google_gemma-3-12b-it-GGUF",
                "google/gemma-3-12b-it-qat-q4_0-gguf",
            ),
            paramsLabel = "12B · Q4",
            note = "Заметно умнее 4B; на телефоне отвечает медленнее, но помещается.",
            approxSizeBytes = 7_300_000_000,
        ),
        LocalModelSeed(
            id = "gemma-3-27b-it-q4",
            title = "Gemma 3 27B Instruct",
            repoIds = listOf(
                "unsloth/gemma-3-27b-it-GGUF",
                "bartowski/google_gemma-3-27b-it-GGUF",
            ),
            paramsLabel = "27B · Q4",
            note = "Самая большая из Gemma. Нужно ~16 ГБ памяти и терпение к скорости.",
            approxSizeBytes = 16_000_000_000,
        ),
        LocalModelSeed(
            id = "qwen3.5-9b-q4",
            title = "Qwen3.5 9B",
            repoIds = listOf(
                "unsloth/Qwen3.5-9B-GGUF",
                "bartowski/Qwen3.5-9B-GGUF",
            ),
            paramsLabel = "9B · Q4",
            note = "Новейший Qwen; сильна в коде и языках.",
            approxSizeBytes = 5_500_000_000,
            capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING, Capability.CODING),
        ),
        LocalModelSeed(
            id = "qwen3.5-4b-q4",
            title = "Qwen3.5 4B",
            repoIds = listOf(
                "unsloth/Qwen3.5-4B-GGUF",
                "bartowski/Qwen3.5-4B-GGUF",
            ),
            paramsLabel = "4B · Q4",
            note = "Новейший Qwen среднего размера.",
            approxSizeBytes = 2_500_000_000,
            capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING, Capability.CODING),
        ),
        LocalModelSeed(
            id = "qwen3.5-0.8b-q4",
            title = "Qwen3.5 0.8B",
            repoIds = listOf(
                "unsloth/Qwen3.5-0.8B-GGUF",
            ),
            paramsLabel = "0.8B · Q4",
            note = "Совсем маленькая — для слабых устройств или быстрой проверки.",
            approxSizeBytes = 550_000_000,
            capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING, Capability.CODING),
        ),
        LocalModelSeed(
            id = "llama-3.1-8b-instruct-q4",
            title = "Llama 3.1 8B Instruct",
            repoIds = listOf(
                "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF",
                "unsloth/Meta-Llama-3.1-8B-Instruct-GGUF",
            ),
            paramsLabel = "8B · Q4",
            note = "Классика; хорошо держит длинный диалог.",
            approxSizeBytes = 4_900_000_000,
        ),

        // LiteRT-LM (Google Tensor SDK) entries. A different runtime, a
        // different file format (.litertlm, resolved the same way as GGUF —
        // see LocalModelSeed.extension), and — on a Pixel with the Tensor
        // SDK's native libraries present — the only path in this app that
        // can run on the TPU/NPU instead of the CPU (see
        // ai.localstudio.app.litert.LiteRtRuntime). On any other device
        // canRun() simply reports false and this app falls back to the
        // GGUF/llama.cpp entries above, the same as an ABI this device
        // doesn't support.
        LocalModelSeed(
            id = "gemma3-1b-it-litert",
            title = "Gemma 3 1B IT (Tensor SDK)",
            repoIds = listOf("litert-community/Gemma3-1B-IT"),
            paramsLabel = "1B · LiteRT-LM",
            note = "Тот же класс модели, что и обычная Gemma 3 1B, но через Google Tensor SDK — может считаться на TPU/NPU Pixel вместо CPU.",
            approxSizeBytes = 800_000_000,
            runtime = RuntimeKind.LITERT,
        ),
        LocalModelSeed(
            id = "gemma-4-e2b-it-litert",
            title = "Gemma 4 E2B IT (Tensor SDK)",
            repoIds = listOf("litert-community/gemma-4-E2B-it-litert-lm"),
            paramsLabel = "E2B · LiteRT-LM",
            note = "Компактная Gemma 4 через Google Tensor SDK — может считаться на TPU/NPU Pixel вместо CPU.",
            approxSizeBytes = 1_500_000_000,
            runtime = RuntimeKind.LITERT,
        ),
    )

    /** Anything the user pastes as `owner/repo` becomes a seed of its own. */
    fun custom(repoId: String): LocalModelSeed = LocalModelSeed(
        id = "custom-" + repoId.replace('/', '_').lowercase(),
        title = repoId.substringAfterLast('/'),
        repoIds = listOf(repoId),
        paramsLabel = "своя модель",
        note = repoId,
        approxSizeBytes = 0,
    )

    fun byId(id: String): LocalModelSeed? = SEEDS.firstOrNull { it.id == id }
}
