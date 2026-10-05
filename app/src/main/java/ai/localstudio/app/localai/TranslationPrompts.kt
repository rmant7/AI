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

    /**
     * The prompt a local model takes for a translation, by the format it was
     * trained on -- the one place that decides it, for the Translation
     * screen and the SDK alike. [isoScriptCode] turns a language code into
     * `ron_Latn` form (Android's ICU has the data; null when it has none).
     */
    fun forLocalModel(
        format: Format,
        sourceName: String,
        targetName: String,
        targetCode: String,
        text: String,
        isoScriptCode: (String) -> String? = { null },
    ): String = when (format) {
        // MADLAD-400: fine-tuned on `<2xx> source text` and nothing else; an
        // instruction around it is just more text for the encoder to (mis)translate.
        Format.TARGET_TAG -> "<2$targetCode> $text"
        // OmniTranslate's model card format, target only. Given the chat
        // instruction it decided on its own the task was "EN->sul_Latn" and
        // answered in Spanish (real device report).
        Format.OMNI_TRANSLATE -> "Translate to ${isoScriptCode(targetCode) ?: targetName}: $text"
        Format.CHAT_INSTRUCTION -> chatInstruction(sourceName, targetName, text)
    }

    enum class Format {
        TARGET_TAG, OMNI_TRANSLATE, CHAT_INSTRUCTION;

        companion object {
            /** From what the model is: a T5 encoder-decoder, an OmniTranslate model (by its name), or an ordinary instruction-following one. */
            fun of(isT5EncoderDecoder: Boolean, modelName: String): Format = when {
                isT5EncoderDecoder -> TARGET_TAG
                modelName.contains("omnitranslate", ignoreCase = true) -> OMNI_TRANSLATE
                else -> CHAT_INSTRUCTION
            }
        }
    }
}
