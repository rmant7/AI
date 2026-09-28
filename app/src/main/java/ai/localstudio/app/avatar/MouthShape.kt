package ai.localstudio.app.avatar

/**
 * A crude articulation bucket for one character of spoken text — not a
 * phoneme, and not meant to become one. [VisemeMapper] assigns one of these
 * per character from nothing but the letter itself, which is enough for a
 * placeholder mouth to look roughly right without any audio analysis, TTS
 * phoneme API, or ML model. See the `avatar` branch's own scope notes for
 * why a real viseme/phoneme system is deliberately out of scope until this
 * cheaper approximation is proven not to be enough.
 */
enum class MouthShape {
    CLOSED,
    OPEN,
    ROUND,
    WIDE,
    TEETH,
    SIBILANT,
    NARROW,
}
