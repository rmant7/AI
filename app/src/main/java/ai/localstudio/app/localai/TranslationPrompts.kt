package ai.localstudio.app.localai

/**
 * The translation prompt a generic instruction-following model gets -- the
 * one place it is written, so the Translation screen and a candidate's
 * translation check send the same text. A check that asked differently
 * would verify a path the product never takes (build #471: a candidate was
 * judged on "Translate into French. Reply with the translation only.",
 * a prompt the Translation screen never sends).
 */
object TranslationPrompts {
    fun chatInstruction(sourceLanguage: String, targetLanguage: String, text: String): String =
        "You are a translation engine. Translate the text between triple backticks " +
            "from $sourceLanguage to $targetLanguage. " +
            "Reply with only the translation itself, nothing else — no quotes, no notes, no explanation.\n\n" +
            "```\n$text\n```"
}
