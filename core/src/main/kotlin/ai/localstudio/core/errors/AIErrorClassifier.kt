package ai.localstudio.core.errors

import ai.localstudio.core.engine.NoModelForCapabilityException
import ai.localstudio.core.runtime.InsufficientMemoryException
import ai.localstudio.core.runtime.ModelLoadException
import ai.localstudio.core.runtime.OperationTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns whatever a provider or a local runtime actually threw into one
 * [AIError]. This is the single place that knows how to read an
 * [HttpStatusError]'s status/body, or recognize one of `core`'s own runtime
 * exceptions — every caller downstream (a retry policy, a fallback chain, a
 * log line) should go through this rather than pattern-matching exception
 * types itself, the same way every provider used to have its own scattered
 * `when (e)` before this existed.
 *
 * Deliberately not called on [CancellationException] (including
 * [TimeoutCancellationException] from a `withTimeout` a *caller* set up, as
 * opposed to [OperationTimeoutException] this app throws itself) — a
 * cancelled coroutine must keep propagating as one, or structured
 * concurrency breaks. Callers check for it first and rethrow, same as every
 * existing catch block in this codebase already does.
 */
object AIErrorClassifier {

    fun classify(t: Throwable): AIError = when (t) {
        is AIError -> t
        is CancellationException -> throw t
        is HttpStatusError -> fromHttpStatus(t.status, t.body, t.message ?: "", t)
        is InsufficientMemoryException -> AIError(AIErrorCode.OUT_OF_MEMORY, t.message ?: "Out of memory", t)
        is OperationTimeoutException -> AIError(AIErrorCode.TIMEOUT, t.message ?: "Operation timed out", t)
        is NoModelForCapabilityException -> AIError(AIErrorCode.UNSUPPORTED, t.message ?: "No model for this capability", t)
        is ModelLoadException -> AIError(AIErrorCode.RUNTIME_ERROR, t.message ?: "Model failed to load", t)
        is SocketTimeoutException -> AIError(AIErrorCode.TIMEOUT, t.message ?: "Timed out", t)
        is UnknownHostException, is ConnectException -> AIError(AIErrorCode.NETWORK, t.message ?: "Network unreachable", t)
        is IOException -> AIError(AIErrorCode.NETWORK, t.message ?: "Network error", t)
        else -> AIError(AIErrorCode.UNKNOWN, t.message ?: t.javaClass.simpleName, t)
    }

    /**
     * [message] is what the classified [AIError] describes itself with —
     * usually the same text [body] would produce, but a caller that has
     * already extracted the provider's own human-readable sentence out of a
     * JSON [body] (a provider's error responses aren't all shaped alike)
     * passes that instead of making [AIError.message] a raw JSON blob.
     */
    private fun fromHttpStatus(status: Int, body: String, message: String, cause: Throwable): AIError {
        val retryAfterMs = retryAfterMs(message)
        val code = when (status) {
            401, 403 -> AIErrorCode.AUTHENTICATION
            404 -> AIErrorCode.NOT_FOUND
            // 413 (request too large): retrying the exact same prompt
            // unchanged fails the same way, same as a malformed body does —
            // and every sibling model on the same provider almost certainly
            // shares the same context-length limit, so a caller iterating
            // candidates is right to skip the rest of that provider's
            // models for this turn on any INVALID_REQUEST, not just a 413.
            400, 413, 422 -> AIErrorCode.INVALID_REQUEST
            429 -> if (looksLikeDailyQuota(message)) AIErrorCode.QUOTA else AIErrorCode.RATE_LIMIT
            in 500..599 -> AIErrorCode.UNAVAILABLE
            else -> AIErrorCode.UNKNOWN
        }
        return AIError(code, message, cause, providerStatus = status, providerBody = body, retryAfterMs = retryAfterMs)
    }

    /**
     * A limit type that resets on its own schedule (TPD/RPD, or the literal
     * "per day") rather than a per-minute burst — the same distinction
     * [ai.localstudio.openai.OpenAiRuntime] already drew for Groq's free
     * tier, moved here so every provider's 429 gets it, not just OpenAI-
     * compatible ones.
     */
    private fun looksLikeDailyQuota(message: String): Boolean = DAILY_LIMIT_PATTERN.containsMatchIn(message)

    /**
     * A provider's own stated wait, parsed out of its error message — e.g.
     * Groq's "...Please try again in 15.84s." A daily quota's own reset time
     * isn't a few seconds away, so [looksLikeDailyQuota] messages are never
     * given a [retryAfterMs] here even if they happen to also match the
     * pattern below; the +2s pads whatever clock skew separates this device
     * from the provider's own countdown.
     */
    private fun retryAfterMs(message: String): Long? {
        if (DAILY_LIMIT_PATTERN.containsMatchIn(message)) return null
        return RETRY_AFTER_PATTERN.find(message)
            ?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { seconds -> (seconds * 1000).toLong() + 2_000L }
    }

    private val RETRY_AFTER_PATTERN = Regex("""try again in ([0-9]+(?:\.[0-9]+)?)\s*s""", RegexOption.IGNORE_CASE)
    private val DAILY_LIMIT_PATTERN = Regex("""per day|\(TPD\)|\(RPD\)""", RegexOption.IGNORE_CASE)
}
