package ai.localstudio.openai

/** Result of testing whether an API key actually works against a provider. */
sealed interface ApiKeyValidationResult {
    /** The provider accepted the key. */
    data object Valid : ApiKeyValidationResult

    /** The provider rejected the key itself (401/403) — this key is bad, not just busy. */
    data class Invalid(val reason: String) : ApiKeyValidationResult

    /** The key may well be fine; the provider is rate-limiting this check specifically. */
    data object RateLimited : ApiKeyValidationResult

    /** Network failure, an unexpected status, or a provider whose `/models` endpoint behaves
     * differently than assumed — inconclusive either way, not proof the key is bad. */
    data class Unknown(val message: String) : ApiKeyValidationResult
}

/**
 * Checks a key with the cheapest call every OpenAI-compatible provider this
 * app targets is expected to support: `GET /models`. Unlike a chat
 * completion, listing models costs no generation quota, so validating a key
 * someone just typed in does not itself count against the same daily limit
 * the key pool exists to work around.
 */
object ApiKeyValidator {

    fun validate(baseUrl: String, apiKey: String, timeoutMs: Int = 10_000): ApiKeyValidationResult {
        val http = HttpTransport(connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs, apiKey = apiKey)
        return try {
            http.get("${baseUrl.trimEnd('/')}/models").text()
            ApiKeyValidationResult.Valid
        } catch (e: OpenAiException) {
            when (e.status) {
                401, 403 -> ApiKeyValidationResult.Invalid(e.message ?: "unauthorized")
                429 -> ApiKeyValidationResult.RateLimited
                else -> ApiKeyValidationResult.Unknown(e.message ?: "HTTP ${e.status}")
            }
        } catch (e: Exception) {
            ApiKeyValidationResult.Unknown(e.message ?: e.toString())
        }
    }

    /**
     * Same idea as [validate], for Anthropic's own Messages API — its
     * `GET /models` needs `x-api-key`/`anthropic-version` headers, not a
     * bearer token, so it can't go through [HttpTransport]/[OpenAiException]
     * the way every other provider's validation does (see [AnthropicRuntime]'s
     * own doc comment on why it has its own small HTTP client too).
     */
    fun validateAnthropic(
        baseUrl: String,
        apiKey: String,
        apiVersion: String = "2023-06-01",
        timeoutMs: Int = 10_000,
    ): ApiKeyValidationResult = try {
        anthropicGet("${baseUrl.trimEnd('/')}/models", apiKey, apiVersion, timeoutMs)
        ApiKeyValidationResult.Valid
    } catch (e: AnthropicException) {
        when (e.status) {
            401, 403 -> ApiKeyValidationResult.Invalid(e.message ?: "unauthorized")
            429 -> ApiKeyValidationResult.RateLimited
            else -> ApiKeyValidationResult.Unknown(e.message ?: "HTTP ${e.status}")
        }
    } catch (e: Exception) {
        ApiKeyValidationResult.Unknown(e.message ?: e.toString())
    }

    /** A plain authenticated GET against Anthropic's API — throws [AnthropicException] on a non-2xx status. */
    private fun anthropicGet(url: String, apiKey: String, apiVersion: String, timeoutMs: Int): String {
        val connection = (java.net.URI.create(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("anthropic-version", apiVersion)
            setRequestProperty("x-api-key", apiKey)
        }
        val status = connection.responseCode
        return if (status in 200..299) {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { connection.disconnect() }
        } else {
            val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw AnthropicException(status, error)
        }
    }

    /**
     * The same `GET /models` call [validate] already makes, but keeping the
     * body it discards — parsed into provider-agnostic
     * [ai.localstudio.core.provider.DiscoveredModel]s
     * ([ai.localstudio.core.provider.OpenAiModelsListParser] does the actual
     * parsing; this only fetches). Empty on any failure (network, a bad key,
     * a provider whose `/models` doesn't answer this shape) rather than
     * throwing — a caller populating a model catalog in the background
     * should degrade to "nothing new discovered," not crash the catalog
     * refresh over one provider being briefly unreachable. [validate]'s own
     * distinction between "key is bad" and "couldn't tell" doesn't apply
     * here: discovery has nothing useful to do with either outcome beyond
     * finding no models.
     */
    fun discoverModels(
        baseUrl: String,
        apiKey: String?,
        timeoutMs: Int = 10_000,
    ): List<ai.localstudio.core.provider.DiscoveredModel> {
        val http = HttpTransport(connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs, apiKey = apiKey)
        return runCatching {
            val body = http.get("${baseUrl.trimEnd('/')}/models").text()
            ai.localstudio.core.provider.OpenAiModelsListParser.parse(body)
        }.getOrDefault(emptyList())
    }
}
