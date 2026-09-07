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
}
