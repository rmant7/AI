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
    /**
     * The exact name of this model's vision-encoder companion file in the
     * same repo(s), when it has one — llama.cpp keeps it separate from the
     * main GGUF ("mmproj"), so this is a second download, not a variant of
     * the first. Resolved by exact name, not [ArtifactResolver]'s quant
     * heuristic: a repo's mmproj file doesn't carry a quant tag to match on,
     * and guessing among a repo's other files risked grabbing the wrong one
     * silently. Null means "this seed has no known projector" — the model
     * still downloads and runs, just text-only, exactly as before this field
     * existed.
     */
    val mmprojFileName: String? = null,
    /**
     * A rough size for [mmprojFileName], same spirit as [approxSizeBytes] for
     * the main GGUF — shown alongside it so the size quoted before download
     * matches what actually lands on disk. Left at 0 for every seed with no
     * projector, and harmless to omit for one that has one: it only makes the
     * upfront estimate optimistic, never wrong in the dangerous direction.
     */
    val mmprojApproxSizeBytes: Long = 0,
)

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
            note = "Новейшая Gemma; сопоставима по размеру с Gemma 3 4B. Понимает прикреплённые изображения.",
            approxSizeBytes = 2_600_000_000,
            // Confirmed present in this exact repo (unsloth/gemma-4-E4B-it-GGUF/
            // blob/main/mmproj-F16.gguf) rather than assumed from a naming
            // convention — the same mistake that cost real debugging time
            // elsewhere in this app's history (a wrongly-guessed exact file
            // name for a different runtime). Picked as the pilot for on-device
            // vision precisely because this model is already in the catalog
            // and already confirmed working text-only.
            mmprojFileName = "mmproj-F16.gguf",
            mmprojApproxSizeBytes = 990_000_000,
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
