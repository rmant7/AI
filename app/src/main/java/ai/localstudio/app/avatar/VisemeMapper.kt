package ai.localstudio.app.avatar

/**
 * Character → [MouthShape], covering Cyrillic and Latin letters — a rough
 * articulation guess, not phonetic transcription: it looks only at the one
 * character `android.speech.tts.TextToSpeech`'s own
 * `UtteranceProgressListener.onRangeStart` callback reports as currently
 * being spoken, with no context, no phoneme dictionary, and no per-language
 * branching. Good enough for a mouth that opens roughly when it should;
 * anything more accurate is real phoneme work, out of scope for this
 * skeleton (see the `avatar` branch's own scope notes).
 */
object VisemeMapper {

    /** [MouthShape.CLOSED] for anything not in the table below — punctuation, digits, whitespace. */
    fun shapeFor(char: Char): MouthShape = TABLE[char.lowercaseChar()] ?: MouthShape.CLOSED

    /** The shape for the middle character of [text]`.substring(start, end)`, or [MouthShape.CLOSED] for an empty/out-of-range range. */
    fun shapeForRange(text: String, start: Int, end: Int): MouthShape {
        if (start < 0 || end > text.length || start >= end) return MouthShape.CLOSED
        return shapeFor(text[(start + end - 1) / 2])
    }

    // Table keys are all lowercase — shapeFor() lowercases before lookup, so
    // an uppercase entry here would simply never match.
    private val TABLE: Map<Char, MouthShape> = buildMap {
        // Closed / bilabial — lips touch.
        for (c in "мбпmbp") put(c, MouthShape.CLOSED)

        // Round — lips pushed forward and rounded.
        for (c in "оуюou") put(c, MouthShape.ROUND)

        // Open — jaw drops, lips apart.
        for (c in "аяэae") put(c, MouthShape.OPEN)

        // Wide — lips spread horizontally.
        for (c in "иыеiy") put(c, MouthShape.WIDE)

        // Teeth — upper teeth on lower lip.
        for (c in "фвfv") put(c, MouthShape.TEETH)

        // Sibilant — narrow, slightly rounded opening.
        for (c in "шжщчszcj") put(c, MouthShape.SIBILANT)

        // Narrow — tongue against the ridge behind the teeth, small opening.
        for (c in "тднлtdnl") put(c, MouthShape.NARROW)
    }
}
