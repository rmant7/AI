package ai.localstudio.openai

/**
 * Server-sent events, reduced to what a chat completion stream actually uses.
 *
 * Kept pure and separate from the HTTP client so the fiddly parts — the
 * terminating sentinel, keep-alive comments, blank lines between events — are
 * testable without a socket.
 */
object SseParser {

    const val DONE = "[DONE]"

    /**
     * Extracts the payload of one SSE line, or null when the line carries no
     * data (comment, event name, blank separator).
     */
    fun dataOf(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim().takeIf { it.isNotEmpty() }
    }

    fun isTerminator(payload: String): Boolean = payload == DONE
}
