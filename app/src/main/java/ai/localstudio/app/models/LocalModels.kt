package ai.localstudio.app.models

import ai.localstudio.core.capability.Capability

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
 * The list carries repositories, never file names: the concrete GGUF is
 * resolved at download time (see [HuggingFaceResolver]).
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
)

object LocalModels {

    val SEEDS = listOf(
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
            id = "qwen2.5-7b-instruct-q4",
            title = "Qwen2.5 7B Instruct",
            repoIds = listOf(
                "bartowski/Qwen2.5-7B-Instruct-GGUF",
                "Qwen/Qwen2.5-7B-Instruct-GGUF",
            ),
            paramsLabel = "7B · Q4",
            note = "Сильна в коде и языках.",
            approxSizeBytes = 4_700_000_000,
            capabilities = setOf(Capability.TEXT_GENERATION, Capability.REASONING, Capability.CODING),
        ),
        LocalModelSeed(
            id = "qwen2.5-3b-instruct-q4",
            title = "Qwen2.5 3B Instruct",
            repoIds = listOf(
                "bartowski/Qwen2.5-3B-Instruct-GGUF",
                "Qwen/Qwen2.5-3B-Instruct-GGUF",
            ),
            paramsLabel = "3B · Q4",
            note = "Компромисс между 1B и 7B.",
            approxSizeBytes = 2_000_000_000,
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
