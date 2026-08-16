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

    val ALL = listOf(
        DEMO,
        CloudProvider(
            id = "gemini",
            title = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-2.0-flash",
            keyHint = "Ключ: aistudio.google.com → Get API key",
        ),
        CloudProvider(
            id = "mistral",
            title = "Mistral",
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest",
            keyHint = "Ключ: console.mistral.ai → API keys",
        ),
        CloudProvider(
            id = "groq",
            title = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            keyHint = "Ключ: console.groq.com → API keys",
        ),
        CloudProvider(
            id = "xai",
            title = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-2-latest",
            keyHint = "Ключ: console.x.ai",
        ),
        CloudProvider(
            id = "openrouter",
            title = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "meta-llama/llama-3.3-70b-instruct:free",
            keyHint = "Ключ: openrouter.ai → Keys. Модели с суффиксом :free бесплатны.",
        ),
        CloudProvider(
            id = "custom",
            title = "Свой сервер (Ollama, llama-server)",
            baseUrl = "http://192.168.1.10:11434/v1",
            defaultModel = "qwen3:8b",
            keyHint = "Адрес OpenAI-совместимого сервера в вашей сети.",
            needsKey = false,
            editableUrl = true,
        ),
    )

    fun byId(id: String?): CloudProvider = ALL.firstOrNull { it.id == id } ?: DEMO
}
