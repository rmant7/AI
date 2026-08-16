package ai.localstudio.app.models

import ai.localstudio.core.capability.Capability

/**
 * A model this app knows how to fetch and run on the device.
 *
 * Carries a *repository*, not a file name: quantised-model repos are
 * restructured on a timescale of weeks, so the concrete GGUF is resolved at
 * browse time (see [HuggingFaceResolver]) rather than baked into a release.
 */
data class LocalModelSeed(
    val id: String,
    val title: String,
    val repoId: String,
    val paramsLabel: String,
    val note: String,
    val capabilities: Set<Capability> = setOf(Capability.TEXT_GENERATION, Capability.REASONING),
    val contextTokens: Int = 4096,
)

object LocalModels {

    /**
     * Deliberately short. Every entry has to be worth a multi-gigabyte download
     * on a phone, and a list of forty is a way of not choosing.
     */
    val SEEDS = listOf(
        LocalModelSeed(
            id = "gemma-3-4b-it-q4",
            title = "Gemma 3 4B Instruct",
            repoId = "google/gemma-3-4b-it-qat-q4_0-gguf",
            paramsLabel = "4B · Q4",
            note = "Хороший баланс качества и скорости для телефона с 8+ ГБ.",
        ),
        LocalModelSeed(
            id = "gemma-3-12b-it-q4",
            title = "Gemma 3 12B Instruct",
            repoId = "google/gemma-3-12b-it-qat-q4_0-gguf",
            paramsLabel = "12B · Q4",
            note = "Заметно умнее, но требует много памяти и отвечает медленнее.",
            contextTokens = 4096,
        ),
        LocalModelSeed(
            id = "gemma-3-1b-it-q4",
            title = "Gemma 3 1B Instruct",
            repoId = "google/gemma-3-1b-it-qat-q4_0-gguf",
            paramsLabel = "1B · Q4",
            note = "Быстрая и маленькая: для проверки, что всё работает.",
        ),
        LocalModelSeed(
            id = "qwen2.5-3b-instruct-q4",
            title = "Qwen2.5 3B Instruct",
            repoId = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            paramsLabel = "3B · Q4",
            note = "Альтернатива Gemma, сильна в коде и языках.",
        ),
    )

    fun byId(id: String): LocalModelSeed? = SEEDS.firstOrNull { it.id == id }
}
