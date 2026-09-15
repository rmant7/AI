package ai.localstudio.core.speech

/**
 * One routing decision, whatever it was — including "kept the current
 * model" (see [DefaultStreamingSpeechRouter]'s own doc comment on why every
 * LID window is recorded, not only switches). Without this, a bad
 * transcript during the experimental phase has no way to tell "LID guessed
 * wrong" apart from "LID was right but the router wouldn't switch" apart
 * from "the model itself is the problem" — see docs/14-vosk-spike.md-style
 * per-run reports, which this is meant to make possible for the router
 * layer the same way.
 */
data class RoutingDecision(
    val timestampMs: Long,
    val lidResult: LanguageIdResult,
    val previousLanguage: Language?,
    val selectedLanguage: Language,
    val previousModelId: String?,
    val selectedModelId: String?,
    val reason: String,
)
