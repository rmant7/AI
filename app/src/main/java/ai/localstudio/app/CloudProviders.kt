package ai.localstudio.app

/**
 * A cloud model behind an API key, with no server for the user to run.
 *
 * Every provider here speaks the OpenAI-compatible protocol, which is why the
 * app needs no code per provider: the same [ai.localstudio.openai.OpenAiRuntime]
 * talks to all of them, and a preset is just a base URL and a default model.
 * Adding a provider is one line in this list.
 *
 * The model field stays editable on purpose. Provider catalogues and free
 * tiers change on a timescale of weeks, so a name hard-coded here will be
 * wrong before long; when it is, the server's own error message is shown
 * verbatim rather than swallowed.
 */
data class CloudProvider(
    val id: String,
    val title: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val needsKey: Boolean = true,
    val editableUrl: Boolean = false,
    /**
     * Model IDs actually reachable on this provider's free tier, offered as
     * tap-to-fill suggestions next to the model field — which stays editable,
     * since this list will drift out of date before the code does.
     */
    val freeModels: List<String> = emptyList(),
)

object CloudProviders {

    val DEMO = CloudProvider(
        id = "demo",
        title = "Демо-режим (без сети)",
        baseUrl = "",
        defaultModel = "stub",
        keyHint = "Ключ не нужен: отвечает встроенный runtime, видно маршрут и контекст.",
        needsKey = false,
    )

    val LOCAL = CloudProvider(
        id = "local",
        title = "Локально на устройстве (llama.cpp)",
        baseUrl = "",
        defaultModel = "gemma-4-e4b-it-q4",
        keyHint = "Ключ и сеть не нужны. Модель скачивается один раз на экране «Модели» и дальше работает офлайн.",
        needsKey = false,
    )

    val ALL = listOf(
        LOCAL,
        DEMO,
        CloudProvider(
            id = "gemini",
            title = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-3.7-flash",
            keyHint = "Ключ: aistudio.google.com → Get API key. Бесплатный уровень — только Flash/Flash-Lite; Pro — платно.",
            freeModels = listOf(
                "gemini-3.7-flash",
                "gemini-3.5-flash",
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash",
                "gemini-2.0-flash-lite",
            ),
        ),
        CloudProvider(
            id = "mistral",
            title = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest",
            keyHint = "Ключ: console.mistral.ai → API keys. Бесплатный уровень Experiment даёт доступ ко всем моделям с ограничением по скорости.",
            freeModels = listOf(
                "mistral-small-latest",
                "devstral-small-latest",
                "ministral-8b-latest",
                "mistral-large-latest",
                "codestral-latest",
            ),
        ),
        CloudProvider(
            id = "groq",
            title = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "openai/gpt-oss-120b",
            keyHint = "Ключ: console.groq.com → API keys. Бесплатно, без карты — лимит по запросам в день.",
            freeModels = listOf(
                "openai/gpt-oss-120b",
                "openai/gpt-oss-20b",
                "qwen/qwen3.6-27b",
                "meta-llama/llama-4-scout-17b-16e-instruct",
                "meta-llama/llama-4-maverick-17b-128e-instruct",
            ),
        ),
        CloudProvider(
            id = "xai",
            title = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-4-fast",
            keyHint = "Ключ: console.x.ai. Бесплатных моделей нет — при регистрации дают \$25 кредита, дальше платно.",
            freeModels = listOf(
                "grok-4-fast",
                "grok-4.1-fast",
                "grok-code-fast-1",
            ),
        ),
        CloudProvider(
            id = "openrouter",
            title = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "meta-llama/llama-3.3-70b-instruct:free",
            keyHint = "Ключ: openrouter.ai → Keys. Модели с суффиксом :free бесплатны, но список часто меняется.",
            freeModels = listOf(
                "meta-llama/llama-3.3-70b-instruct:free",
                "qwen/qwen-2.5-7b-instruct:free",
            ),
        ),
        CloudProvider(
            id = "custom",
            title = "Свой сервер (Ollama, llama-server)",
            baseUrl = "http://192.168.1.10:11434/v1",
            defaultModel = "qwen3:8b",
            keyHint = "Адрес OpenAI-совместимого сервера в вашей сети.",
            needsKey = false,
            editableUrl = true,
            freeModels = listOf("qwen3:8b", "llama3.1:8b", "gemma3:12b", "deepseek-r1:8b"),
        ),
    )

    fun byId(id: String?): CloudProvider = ALL.firstOrNull { it.id == id } ?: DEMO
}
