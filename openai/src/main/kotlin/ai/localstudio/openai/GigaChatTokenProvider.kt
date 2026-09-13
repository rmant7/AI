package ai.localstudio.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class GigaChatTokenResponse(val access_token: String)

/**
 * GigaChat is the one provider this app targets that does not take a static
 * bearer key directly — what a user (or a bundled build-time secret) actually
 * has is an OAuth client-credentials "authorization key" (base64
 * `client_id:client_secret`), which has to be exchanged for a short-lived
 * access token on a completely different host than the chat API itself
 * before every real request. This wraps that exchange behind
 * [OpenAiConfig.transformKey], so [OpenAiRuntime] itself needs no GigaChat-
 * specific branch: it still just resolves "the current key" from
 * [ai.localstudio.core.keys.ApiKeyRotator] the same way it does for every
 * other provider, and this is what turns that resolved authorization key
 * into a real bearer token right before the request goes out.
 *
 * Not verified end to end against GigaChat's live API — this repo's own
 * build/dev environment cannot reach `devices.sberbank.ru` at all (blocked
 * at the network level), so this is implemented straight from GigaChat's
 * published API reference, not from a working test run. Two things in
 * particular are worth confirming on a real device before trusting this:
 * the exact token lifetime (documented as "valid ~30 minutes," which
 * [TOKEN_LIFETIME_MS] deliberately undercuts rather than trusting a field in
 * the response whose exact semantics — epoch millis vs. something else —
 * this code was not able to verify), and whether the request/response
 * shapes at `/chat/completions` really do match the OpenAI-compatible ones
 * [OpenAiRuntime] already sends for Groq/Gemini.
 */
class GigaChatTokenProvider(
    private val scope: String = "GIGACHAT_API_PERS",
) {
    private data class CachedToken(val accessToken: String, val fetchedAtMs: Long)

    // Keyed by the raw authorization key, not a single slot: a bundled key
    // pool (see BundledApiKeys) can hold more than one GigaChat authorization
    // key, each needing its own independently-cached token.
    private val cache = ConcurrentHashMap<String, CachedToken>()

    suspend fun token(authorizationKey: String): String {
        // Trimmed once, here, so both the cache key and the header value
        // agree on the same string — a key typed or pasted with a trailing
        // newline would otherwise cache under one string and send another.
        val key = authorizationKey.trim()
        val now = System.currentTimeMillis()
        cache[key]?.let { cached ->
            if (now - cached.fetchedAtMs < TOKEN_LIFETIME_MS) return cached.accessToken
        }
        val fetched = fetchToken(key)
        cache[key] = CachedToken(fetched, now)
        return fetched
    }

    private suspend fun fetchToken(authorizationKey: String): String = withContext(Dispatchers.IO) {
        // GigaChat's own reference SDK (ai-forever/gigachat) validates this
        // client-side and warns on exactly this mistake: pasting the Client
        // Secret, or the raw "client_id:client_secret" pair, instead of the
        // already-Base64-encoded "Authorization key" the console's "Get Key"
        // dialog actually shows. Left unvalidated, that mistake used to
        // surface as GigaChat's own opaque `{"code":4,"message":"Can't
        // decode 'Authorization' header"}` — accurate, but not something
        // that tells the user what to actually go fix.
        require(runCatching { java.util.Base64.getDecoder().decode(authorizationKey) }.isSuccess) {
            "This doesn't look like a GigaChat Authorization key (not valid Base64). " +
                "Re-copy it from \"Получить ключ\" / Get Key in the Sber developer console — " +
                "not the Client ID or Client Secret shown next to it."
        }
        val connection = (URI.create(OAUTH_URL).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
            // Required by GigaChat's own API — a fresh uuid4 per request,
            // not a stable per-device or per-key id.
            setRequestProperty("RqUID", UUID.randomUUID().toString())
            setRequestProperty("Authorization", "Basic $authorizationKey")
        }
        connection.outputStream.use { it.write("scope=$scope".toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        connection.disconnect()

        if (status !in 200..299) throw OpenAiException(status, body)
        openAiJson.decodeFromString(GigaChatTokenResponse.serializer(), body).access_token
    }

    private companion object {
        const val OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"

        // GigaChat documents the token as valid for roughly 30 minutes.
        // Refreshing at 20 rather than trusting that number to the minute —
        // and rather than parsing the response's own expiry field, whose
        // exact units this code could not confirm — costs one extra token
        // fetch per half hour of active use at worst, against risking a
        // request failing on an unexpectedly-already-expired token.
        const val TOKEN_LIFETIME_MS = 20 * 60 * 1000L
    }
}
