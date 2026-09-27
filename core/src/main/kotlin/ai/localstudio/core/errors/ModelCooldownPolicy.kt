package ai.localstudio.core.errors

/**
 * Which (provider, model) pairs are temporarily not worth trying — the
 * contract [ai.localstudio.app.routing.ModelCooldownStore] (file-backed,
 * Android) implements. Pulled out to `core` so a future `core`-level
 * candidate-building/fallback engine (docs/17-ai-core-stage1.md, sessions
 * 6–7) can depend on this without depending on Android, and so it can be
 * faked in a test instead of needing a real [android.content.Context].
 */
interface ModelCooldownPolicy {
    fun isOnCooldown(providerId: String, modelName: String): Boolean
    fun markOverloaded(providerId: String, modelName: String)

    /** How long the cooldown [markOverloaded] just wrote actually runs — for logging. */
    fun currentCooldownMs(providerId: String, modelName: String): Long
}

/**
 * What an [AIErrorCode] means for a fallback chain iterating several
 * candidates (see [ai.localstudio.core.runtime.FallbackTextRuntime]) —
 * pulled out of the `when (error) { is OpenAiException && error.status == … }`
 * checks that used to live in application code (AppContainer.cloudCandidates),
 * so the same decision applies to any provider's [AIError], not just an
 * OpenAI-compatible endpoint's.
 */
enum class FallbackAction {
    /** This specific model is unhealthy right now — cool it down, other models on the same provider are unaffected. */
    COOLDOWN_MODEL,

    /** This request (as shaped/sized) will fail identically on every sibling model of the same provider this turn. */
    SKIP_PROVIDER_THIS_TURN,

    /** Nothing beyond the ordinary "this candidate failed, try the next one" [ai.localstudio.core.runtime.FallbackTextRuntime] already does. */
    NONE,
}

object FallbackPolicy {
    fun actionFor(code: AIErrorCode): FallbackAction = when (code) {
        AIErrorCode.UNAVAILABLE -> FallbackAction.COOLDOWN_MODEL
        AIErrorCode.INVALID_REQUEST -> FallbackAction.SKIP_PROVIDER_THIS_TURN
        else -> FallbackAction.NONE
    }

    /**
     * Whether a 429-shaped failure is worth rotating to the pool's next key
     * for — both [AIErrorCode.RATE_LIMIT] (a transient per-minute burst) and
     * [AIErrorCode.QUOTA] (an exhausted daily allowance) are, since a
     * *different* key may have its own separate quota; [AIError.retryAfterMs]
     * (null for a daily quota) is what already tells the caller how long to
     * cool the exhausted key down for, not this.
     */
    fun shouldRotateKey(code: AIErrorCode): Boolean = code == AIErrorCode.RATE_LIMIT || code == AIErrorCode.QUOTA
}
