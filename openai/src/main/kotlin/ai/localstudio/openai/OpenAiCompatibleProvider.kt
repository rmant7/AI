package ai.localstudio.openai

import ai.localstudio.core.provider.AIProvider
import ai.localstudio.core.provider.DiscoveredModel
import ai.localstudio.core.runtime.ModelRuntime

/**
 * [AIProvider] for the one runtime every cloud provider this app targets —
 * Groq, Gemini's own OpenAI-compatible layer, Mistral, xAI, OpenRouter, and
 * a custom `llama-server`/Ollama endpoint — actually needs: [OpenAiRuntime]
 * itself, parameterized by [OpenAiConfig] exactly the way
 * `AppContainer.cloudCandidates` already builds one per provider today. This
 * doesn't replace that construction (docs/17-ai-core-stage1.md sessions 6-7
 * move it into `core`); it names the same "id + runtime + discovery" shape
 * [AIProvider] declares, using pieces that already exist.
 *
 * [discoverModels] reuses [ApiKeyValidator.discoverModels] — the exact
 * `GET /models` call [config] would authenticate with for a real chat
 * request, parsed into provider-agnostic [DiscoveredModel]s. A provider
 * with no key configured, or momentarily unreachable, discovers nothing
 * rather than failing — same reasoning as [ApiKeyValidator.discoverModels]
 * itself.
 */
class OpenAiCompatibleProvider(
    override val id: String,
    private val config: OpenAiConfig,
) : AIProvider {

    override val runtime: ModelRuntime = OpenAiRuntime(config)

    override suspend fun discoverModels(): List<DiscoveredModel> =
        ApiKeyValidator.discoverModels(config.baseUrl, config.apiKey)
}

/**
 * GigaChat is not a separate implementation — same [OpenAiRuntime], same
 * `GET /models` discovery, the one thing setting it apart
 * ([OpenAiConfig.transformKey] exchanging an OAuth authorization key for a
 * short-lived bearer token before every request — see
 * [GigaChatTokenProvider]'s own doc comment) is already just a config
 * value, not a code path. This factory only saves a caller from wiring
 * [GigaChatTokenProvider] in by hand at every call site.
 */
fun GigaChatProvider(
    apiKey: String?,
    keyRotator: ai.localstudio.core.keys.ApiKeyRotator? = null,
    tokenProvider: GigaChatTokenProvider = GigaChatTokenProvider(),
    baseUrl: String = "https://gigachat.devices.sberbank.ru/api/v1",
): AIProvider = OpenAiCompatibleProvider(
    id = "gigachat",
    config = OpenAiConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        keyRotator = keyRotator,
        transformKey = tokenProvider::token,
    ),
)
