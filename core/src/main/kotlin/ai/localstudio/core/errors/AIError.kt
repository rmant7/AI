package ai.localstudio.core.errors

/**
 * What kind of failure this was, independent of which provider or runtime
 * produced it — the taxonomy every cloud provider's own status codes and
 * every local-runtime exception get sorted into (see [AIErrorClassifier]),
 * so a single retry/fallback policy (docs/17-ai-core-stage1.md, session 2)
 * can act on one enum instead of a growing pile of provider-specific
 * `when (e)` branches.
 */
enum class AIErrorCode {
    /** A key/credential is invalid, expired, or was rejected outright (HTTP 401/403). */
    AUTHENTICATION,

    /** A transient rate limit — the same key will likely work again shortly, or after rotation. */
    RATE_LIMIT,

    /** A quota that resets on its own schedule (daily/monthly), not worth retrying soon. */
    QUOTA,

    /** The request never reached the provider, or the connection was reset/refused. */
    NETWORK,

    /** The provider (or a local operation) took longer than the caller was willing to wait. */
    TIMEOUT,

    /** The provider is reachable but reports itself overloaded/down (HTTP 5xx). */
    UNAVAILABLE,

    /** The requested model/endpoint doesn't exist (HTTP 404, or "model not found" bodies). */
    NOT_FOUND,

    /** The request itself was malformed — retrying it unchanged will fail the same way. */
    INVALID_REQUEST,

    /** The provider refused to answer this specific content (safety filter, moderation). */
    CONTENT_ERROR,

    /** A local runtime failure — a native crash, a decode error — not a network/provider one. */
    RUNTIME_ERROR,

    /** Not enough free memory to load or run the model. */
    OUT_OF_MEMORY,

    /** The request asked for something this model/runtime doesn't support. */
    UNSUPPORTED,

    /** Recognized as a failure, but not classifiable into any of the above. */
    UNKNOWN,
}

/**
 * A provider- and runtime-agnostic failure. [AIErrorClassifier] is the only
 * intended way to produce one — from an [HttpStatusError] a provider threw,
 * or from a plain exception a local runtime threw — so every call site
 * downstream (retry policy, fallback, UI) reasons about [code], not about
 * which of a dozen provider-specific exception types it happened to catch.
 *
 * [providerStatus]/[providerBody] are kept, not discarded, once classified:
 * useful in a log or an error dialog even though the policy itself only
 * looks at [code]. [retryAfterMs] is the provider's own stated wait (a
 * `"try again in 12.4s"` in the body, or an actual `Retry-After` header),
 * when [AIErrorClassifier] found one — null means "no explicit hint",
 * not "retry immediately."
 */
class AIError(
    val code: AIErrorCode,
    message: String,
    cause: Throwable? = null,
    val providerStatus: Int? = null,
    val providerBody: String? = null,
    val retryAfterMs: Long? = null,
) : Exception(message, cause)

/**
 * Implemented by a provider's own HTTP-failure exception (e.g. an
 * OpenAI-compatible endpoint's `OpenAiException`, GigaChat's OAuth
 * exchange failure) so [AIErrorClassifier] can read the status/body without
 * `core` depending on any specific provider module — providers depend on
 * `core`, never the other way around.
 */
interface HttpStatusError {
    val status: Int
    val body: String
}
