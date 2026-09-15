package ai.localstudio.core.speech

/**
 * Tunable thresholds for [StreamingSpeechRouter], kept out of the router's
 * own algorithm so a different policy (fast/accuracy/offline/battery/
 * benchmark — see docs/14) is a new [RoutingPolicy] value, never a new
 * branch in the router's code.
 *
 * The hysteresis these four numbers implement (see
 * [DefaultStreamingSpeechRouter]'s own doc comment) is what stops routing
 * from flapping between models on every LID window — e.g. current=RU,
 * `EN 0.58` doesn't switch (below [switchConfidence]), `EN 0.91` does, but
 * only once it has held for [minStableWindows] consecutive windows.
 */
data class RoutingPolicy(
    /** Below this, a *first* language pick isn't made at all — [Language.UNKNOWN] / the fallback is used instead. */
    val minLanguageConfidence: Float = 0.55f,
    /** Below this, an already-selected language/model is kept even if the latest LID window disagrees. */
    val switchConfidence: Float = 0.75f,
    /** Consecutive LID windows that must agree, each at/above [switchConfidence], before the router actually switches — one strong-but-brief misread must not retarget the model. */
    val minStableWindows: Int = 2,
    /** False pins the router to whatever model started the session — useful for a benchmark run isolating one model's own behavior (see docs/14's benchmark-mode section) without the router's own switching logic in the way. */
    val allowModelSwitching: Boolean = true,
    /** Model id to fall back to when no specialist is registered for the detected language, LID is low-confidence/mixed, or the chosen specialist fails. Null means "no fallback" — the session surfaces an error instead (see docs/14's error-handling section). */
    val fallbackModelId: String? = null,
) {
    companion object {
        val DEFAULT = RoutingPolicy()
    }
}
