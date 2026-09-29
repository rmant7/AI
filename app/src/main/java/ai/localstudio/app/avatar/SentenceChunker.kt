package ai.localstudio.app.avatar

/**
 * Turns a growing stream of generated text into complete sentences, so
 * [AvatarSpeechController] can start speaking well before the whole answer
 * has finished generating — a per-token feed would either be handed to TTS
 * one word at a time (heard as four separate, disjointed utterances instead
 * of one sentence) or held until the end (no earlier than the plain text
 * bubble already shows).
 *
 * Fed the *entire* accumulated answer on every call (matching how
 * `ChatActivity.send()`'s own `onPartialText` already works — see its
 * `partial: MutableStateFlow<String?>`), not a delta: [consume] tracks how
 * much of that growing string it has already turned into sentences itself,
 * so calling it repeatedly with the same or a longer prefix is always safe.
 */
class SentenceChunker(private val maxChars: Int = 160) {

    private var consumedUpTo = 0

    /**
     * Sentences newly completed since the last call, in order. [fullText]
     * must be the same string as last time, or that string plus more text
     * appended — anything shorter (a new turn) needs a fresh [reset] first.
     */
    fun consume(fullText: String): List<String> {
        val sentences = mutableListOf<String>()
        var searchFrom = consumedUpTo
        while (true) {
            val breakAt = fullText.indexOfFirstSentenceEnd(searchFrom)
            if (breakAt == -1) break
            sentences += fullText.substring(consumedUpTo, breakAt + 1).trim()
            consumedUpTo = breakAt + 1
            searchFrom = consumedUpTo
        }
        // Force a flush once unconsumed text alone would exceed maxChars —
        // a model can genuinely run on for many seconds before the next
        // sentence-ending punctuation, and speech (like the chat bubble
        // itself) should not sit silent that whole time.
        if (fullText.length - consumedUpTo >= maxChars) {
            val breakAt = fullText.lastIndexOf(' ', fullText.length - 1).takeIf { it >= consumedUpTo } ?: fullText.length
            val chunk = fullText.substring(consumedUpTo, breakAt).trim()
            if (chunk.isNotEmpty()) sentences += chunk
            consumedUpTo = breakAt
        }
        return sentences.filter { it.isNotBlank() }
    }

    /** Whatever text has accumulated past the last completed/flushed sentence — spoken on [flushRemainder] once generation ends. */
    fun flushRemainder(fullText: String): String? {
        val remainder = fullText.substring(consumedUpTo.coerceAtMost(fullText.length)).trim()
        consumedUpTo = fullText.length
        return remainder.takeIf { it.isNotEmpty() }
    }

    /** Starts over for a new turn — must be called before [consume] sees a `fullText` shorter than the last one. */
    fun reset() {
        consumedUpTo = 0
    }

    private fun String.indexOfFirstSentenceEnd(from: Int): Int {
        if (from >= length) return -1
        for (i in from until length) {
            if (this[i] in SENTENCE_END_CHARS) return i
        }
        return -1
    }

    companion object {
        private val SENTENCE_END_CHARS = charArrayOf('.', '!', '?', '\n')
        private val CLAUSE_END_CHARS = charArrayOf(',', ';', ':', '—')
        private const val MIN_PIECE = 40

        /**
         * [text] cut into pieces of at most [maxChars], at the latest sentence end, else clause end, else
         * space before the limit. One utterance is synthesized whole, so a paragraph without a full stop
         * (or a final flush of a long answer) would keep a slow voice silent for minutes.
         */
        fun splitLong(text: String, maxChars: Int = 160): List<String> {
            val pieces = mutableListOf<String>()
            var rest = text.trim()
            while (rest.length > maxChars) {
                val window = rest.substring(0, maxChars + 1)
                var cut = window.lastIndexOfAny(SENTENCE_END_CHARS)
                if (cut < MIN_PIECE) cut = window.lastIndexOfAny(CLAUSE_END_CHARS)
                if (cut < MIN_PIECE) cut = window.lastIndexOf(' ') - 1
                if (cut < MIN_PIECE) cut = maxChars - 1
                pieces += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1).trim()
            }
            if (rest.isNotEmpty()) pieces += rest
            return pieces.filter { it.isNotBlank() }
        }
    }
}
