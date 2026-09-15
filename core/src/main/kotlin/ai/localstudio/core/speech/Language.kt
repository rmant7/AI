package ai.localstudio.core.speech

/**
 * A controlled, closed set of languages the router reasons about explicitly.
 * Deliberately small for now: adding a language later means adding an entry
 * here, never a parallel string-based path — nothing downstream
 * ([LanguageIdentifier], [SpeechModelRegistry], the router) special-cases a
 * particular value by name, so growing this enum is the only change adding
 * language support to the *routing* layer ever needs (a model's own
 * language support is separate — see [SpeechModelCapabilities.languages]).
 */
enum class Language {
    RU,
    EN,
    HE,
    UNKNOWN,
}
