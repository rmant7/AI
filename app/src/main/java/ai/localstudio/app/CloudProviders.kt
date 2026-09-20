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
     * Whether attaching an image is worth offering for this provider at all —
     * true the moment at least one of its models can actually see one. Only
     * gates the attach button (see ChatActivity); which of THIS provider's
     * several models actually receives the image on a given turn is decided
     * per-model by [visionModels] instead.
     */
    val visionCapable: Boolean = false,
    /**
     * Which of [freeModels] actually accept the OpenAI-compatible vision
     * content shape (`image_url` parts) this app sends — confirmed by an
     * actual failure/success, not assumed. `null` means unverified either
     * way for every model this provider offers: an attached image is still
     * sent and left to fail gracefully per-model, same as before this field
     * existed. A non-null set (including empty) is enforced up front —
     * AppContainer skips a candidate outright rather than spending a real
     * request finding out it doesn't support images, which matters most for
     * a provider like Groq that mixes vision and text-only models under one
     * free-tier rotation: two of its models rejecting the same attached
     * image before reaching the one that actually understands it wasted a
     * full round trip each, observed directly in a live Compare-mode run.
     * An editable model field still means the user can type in a name this
     * set doesn't cover — treated as not vision-capable, the conservative
     * default, rather than spending a request finding out either way.
     */
    val visionModels: Set<String>? = null,
)

object CloudProviders {

    /**
     * Not a real endpoint — [baseUrl] is blank, so [ai.localstudio.app.AppContainer.cloudCandidates]
     * always returns an empty list for it, same as any other provider with
     * no address configured. Deliberately left out of [ALL]: it used to be
     * selectable and toggleable there like a normal provider, but "enabling"
     * it did nothing (it never contributed a real [ai.localstudio.core.runtime.FallbackCandidate]),
     * while [ai.localstudio.app.AppContainer.runtimeLabel] still showed it
     * as though it were part of the active route — confusing, since a
     * person had no way to tell it apart from a provider that actually
     * worked. Its only real job — [ai.localstudio.app.StubRuntime] answering
     * something before any real model or provider is configured — already
     * happens automatically whenever [ai.localstudio.app.AppContainer.orchestrator]
     * finds zero real candidates, with no dependency on this object being
     * selectable anywhere; this is kept only as the label
     * [ai.localstudio.app.AppContainer.runtimeLabel] and [byId]'s own
     * not-found fallback still use.
     */
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

    /**
     * Gemini Nano via AICore — on-device like [LOCAL], but not a file this
     * app downloads or manages itself: [ai.localstudio.app.aicore.AiCoreRuntime]
     * routes to it via [ai.localstudio.app.aicore.AiCorePromptClient], not
     * through [ai.localstudio.app.AppContainer.cloudCandidates] (it doesn't
     * speak the OpenAI-compatible protocol every other entry in [ALL] does),
     * so no [baseUrl]/[freeModels] here actually matter — this exists only
     * so the provider picker in Settings has an enable checkbox for it, same
     * as [LOCAL]. See docs/04-runtime.md's "Gemini Nano / AICore feasibility"
     * section for why this only degrades to the next enabled provider rather
     * than downloading Gemini Nano mid-chat: see [AiCoreRuntime]'s own doc
     * comment.
     */
    val AICORE = CloudProvider(
        id = "aicore",
        titleRes = R.string.provider_title_aicore,
        baseUrl = "",
        defaultModel = "gemini-nano-aicore",
        keyHintRes = R.string.provider_keyhint_aicore,
        needsKey = false,
    )

    /** [DEMO] is deliberately absent — see its own doc comment for why it was never a real, selectable choice here. */
    val ALL = listOf(
        LOCAL,
        AICORE,
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
            visionCapable = true,
            // Confirmed against Groq's own vision docs (console.groq.com/docs/vision):
            // the Llama 4 models and Qwen3.6-27B accept image input, the
            // gpt-oss reasoning models do not — matching a live failure
            // observed directly ("messages[1].content must be a string" from
            // both gpt-oss models, a real answer from qwen3.6-27b).
            visionModels = setOf(
                "qwen/qwen3.6-27b",
                "meta-llama/llama-4-scout-17b-16e-instruct",
                "meta-llama/llama-4-maverick-17b-128e-instruct",
            ),
        ),
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
            id = "gigachat",
            titleRes = R.string.provider_title_gigachat,
            baseUrl = "https://gigachat.devices.sberbank.ru/api/v1",
            defaultModel = "GigaChat-2",
            keyHintRes = R.string.provider_keyhint_gigachat,
            // Model names and free-tier availability are unverified against
            // a live account — GigaChat's own endpoints are unreachable from
            // this app's build/dev environment (blocked at the network
            // level there), so this list is taken from GigaChat's published
            // catalogue rather than confirmed by an actual successful call.
            // The model field stays editable, same as every other provider
            // here, for exactly this kind of drift.
            freeModels = listOf(
                "GigaChat-2",
                "GigaChat-2-Pro",
                "GigaChat-2-Max",
            ),
            // GigaChat's own docs (developers.sber.ru) confirm it CAN read
            // images — but only via a separate two-step flow (upload through
            // POST /files, then reference the returned file id as an
            // `attachment`), not the OpenAI-compatible inline image_url
            // content this app sends. Observed live: every model here
            // rejected an attached image with a flat "invalid JSON syntax" —
            // not a graceful per-model rejection, its parser doesn't
            // recognize this request shape at all. Marked as supporting no
            // vision models for now rather than half-implementing the real
            // upload flow blind (this app's dev environment cannot reach
            // GigaChat's servers to verify it) — a real follow-up, not a
            // one-line fix.
            visionModels = emptySet(),
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
