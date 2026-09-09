package ai.localstudio.app

import androidx.annotation.StringRes

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
    @StringRes val titleRes: Int,
    val baseUrl: String,
    val defaultModel: String,
    @StringRes val keyHintRes: Int,
    val needsKey: Boolean = true,
    val editableUrl: Boolean = false,
    /**
     * Model IDs actually reachable on this provider's free tier, offered as
     * tap-to-fill suggestions next to the model field — which stays editable,
     * since this list will drift out of date before the code does.
     */
    val freeModels: List<String> = emptyList(),
    /**
     * Whether attaching an image is worth offering for this provider at all.
     * Conservative on purpose: only providers actually confirmed to accept
     * OpenAI-compatible vision content (`image_url` parts) are marked true —
     * an editable model field means the user could always type in a model
     * this doesn't hold for, so this is "don't block the common case," not a
     * guarantee every model listed here understands images.
     */
    val visionCapable: Boolean = false,
)

object CloudProviders {

    val DEMO = CloudProvider(
        id = "demo",
        titleRes = R.string.provider_title_demo,
        baseUrl = "",
        defaultModel = "stub",
        keyHintRes = R.string.provider_keyhint_demo,
        needsKey = false,
    )

    val LOCAL = CloudProvider(
        id = "local",
        titleRes = R.string.provider_title_local,
        baseUrl = "",
        defaultModel = "gemma-4-e4b-it-q4",
        keyHintRes = R.string.provider_keyhint_local,
        needsKey = false,
    )

    val ALL = listOf(
        LOCAL,
        DEMO,
        CloudProvider(
            id = "gemini",
            titleRes = R.string.provider_title_gemini,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-3.7-flash",
            keyHintRes = R.string.provider_keyhint_gemini,
            // gemini-2.0-flash-lite deliberately dropped: reported dead/
            // retired, not just occasionally overloaded — the runtime
            // cooldown below handles "overloaded right now", but a model
            // that never comes back has no business being retried forever.
            freeModels = listOf(
                "gemini-3.7-flash",
                "gemini-3.5-flash",
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash",
            ),
            visionCapable = true,
        ),
        CloudProvider(
            id = "mistral",
            titleRes = R.string.provider_title_mistral,
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-small-latest",
            keyHintRes = R.string.provider_keyhint_mistral,
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
            titleRes = R.string.provider_title_groq,
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "openai/gpt-oss-120b",
            keyHintRes = R.string.provider_keyhint_groq,
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
            titleRes = R.string.provider_title_xai,
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-4-fast",
            keyHintRes = R.string.provider_keyhint_xai,
            freeModels = listOf(
                "grok-4-fast",
                "grok-4.1-fast",
                "grok-code-fast-1",
            ),
        ),
        CloudProvider(
            id = "openrouter",
            titleRes = R.string.provider_title_openrouter,
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "meta-llama/llama-3.3-70b-instruct:free",
            keyHintRes = R.string.provider_keyhint_openrouter,
            freeModels = listOf(
                "meta-llama/llama-3.3-70b-instruct:free",
                "qwen/qwen-2.5-7b-instruct:free",
            ),
        ),
        CloudProvider(
            id = "custom",
            titleRes = R.string.provider_title_custom,
            baseUrl = "http://192.168.1.10:11434/v1",
            defaultModel = "qwen3:8b",
            keyHintRes = R.string.provider_keyhint_custom,
            needsKey = false,
            editableUrl = true,
            freeModels = listOf("qwen3:8b", "llama3.1:8b", "gemma3:12b", "deepseek-r1:8b"),
        ),
    )

    fun byId(id: String?): CloudProvider = ALL.firstOrNull { it.id == id } ?: DEMO
}
