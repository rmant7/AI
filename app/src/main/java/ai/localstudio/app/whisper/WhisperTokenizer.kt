package ai.localstudio.app.whisper

import org.json.JSONObject
import java.io.File

/**
 * Turns Whisper's output token ids back into text.
 *
 * VirtualClone's `Whisper_Sizes` branch never actually wired a real Whisper
 * vocabulary in — at runtime it copied an unrelated single-character JSON
 * asset in its place, so `decodeTokens` there would emit raw token numbers,
 * not words, the moment it ran. Whisper's real tokenizer is the standard HF
 * `vocab.json` shipped with every `openai/whisper-*` checkpoint: a
 * `{byte-level-string: id}` map using GPT-2's byte-to-unicode encoding,
 * which is why a token can't just be read off as UTF-8 — each character in
 * the stored string stands for one raw byte via a fixed 256-entry table, and
 * skipping that step is exactly what garbles anything outside ASCII
 * (Cyrillic included).
 */
class WhisperTokenizer private constructor(private val idToToken: Map<Int, String>) {

    /** Whisper's special tokens (`<|startoftranscript|>`, language markers, timestamps, …) start here. */
    private val specialTokenStart = 50257

    fun decode(tokens: List<Int>): String {
        val byteLevel = StringBuilder()
        for (token in tokens) {
            if (token >= specialTokenStart) continue
            idToToken[token]?.let { byteLevel.append(it) }
        }
        val bytes = ByteArray(byteLevel.length)
        var count = 0
        for (ch in byteLevel) {
            val b = UNICODE_TO_BYTE[ch] ?: continue
            bytes[count++] = b
        }
        return String(bytes, 0, count, Charsets.UTF_8).trim()
    }

    companion object {
        fun fromVocabJson(file: File): WhisperTokenizer {
            val json = JSONObject(file.readText())
            val map = HashMap<Int, String>(json.length())
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[json.getInt(key)] = key
            }
            return WhisperTokenizer(map)
        }

        /**
         * GPT-2's byte<->unicode mapping: printable Latin-1 bytes map to
         * themselves, every other byte (controls, DEL, the 128..159/173 gaps)
         * maps to a codepoint starting at U+0100 so the resulting string is
         * always printable/JSON-safe. This is the exact inverse of the table
         * every GPT-2-family BPE vocab (Whisper's included) was built with —
         * decoding without it, as VirtualClone's port did, corrupts any token
         * whose byte fell outside printable Latin-1.
         */
        private val UNICODE_TO_BYTE: Map<Char, Byte> = buildMap {
            val bytesList = mutableListOf<Int>()
            (33..126).forEach { bytesList += it }
            (161..172).forEach { bytesList += it }
            (174..255).forEach { bytesList += it }
            val byteToUnicode = HashMap<Int, Int>()
            bytesList.forEach { byteToUnicode[it] = it }
            var extra = 0
            for (b in 0..255) {
                if (b !in byteToUnicode) {
                    byteToUnicode[b] = 256 + extra
                    extra++
                }
            }
            byteToUnicode.forEach { (byte, codepoint) -> put(codepoint.toChar(), byte.toByte()) }
        }
    }
}
