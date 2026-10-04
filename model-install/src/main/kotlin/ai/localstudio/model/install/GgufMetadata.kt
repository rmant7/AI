package ai.localstudio.model.install

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * A GGUF file's own description of itself -- the header and key/value
 * metadata at the start of the file, which llama.cpp reads before anything
 * else: `general.architecture`, `<arch>.context_length`,
 * `tokenizer.chat_template`, `general.file_type`, ... Read from the bytes,
 * not guessed from a file or repository name: a file can download and hash
 * correctly and still be one the bundled llama.cpp refuses (E5 Small: "bert
 * model needs to define token type count").
 *
 * Only scalar values and strings are kept; arrays (the tokenizer's
 * vocabulary is one, often megabytes) are skipped, their length recorded in
 * [arrayLengths].
 */
data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val values: Map<String, Any>,
    val arrayLengths: Map<String, Long>,
    /** False when reading stopped early ([GgufMetadataReader.read]'s `stopWhen`) -- later keys are then unknown, not absent. */
    val complete: Boolean,
) {
    val architecture: String? get() = values[KEY_ARCHITECTURE] as? String
    val name: String? get() = values["general.name"] as? String
    val sizeLabel: String? get() = values["general.size_label"] as? String
    val fileType: Long? get() = (values["general.file_type"] as? Number)?.toLong()
    val contextLength: Long? get() = architecture?.let { (values["$it.context_length"] as? Number)?.toLong() }
    val hasChatTemplate: Boolean get() = (values[KEY_CHAT_TEMPLATE] as? String)?.isNotBlank() == true
    val vocabularySize: Long? get() = arrayLengths["tokenizer.ggml.tokens"]

    companion object {
        const val KEY_ARCHITECTURE = "general.architecture"
        const val KEY_CHAT_TEMPLATE = "tokenizer.chat_template"
    }
}

class GgufFormatException(message: String) : Exception(message)

/**
 * Streaming reader for the header of a GGUF file (versions 2 and 3,
 * little-endian -- what llama.cpp writes), from any [InputStream]: a local
 * file or the first bytes of a remote one over an HTTP Range request.
 *
 * Layout (llama.cpp's gguf-py/gguf/gguf_reader.py): magic "GGUF", uint32
 * version, uint64 tensor count, uint64 key/value count, then each key as a
 * uint64-length UTF-8 string, a uint32 value type and the value. Strings are
 * uint64-length; arrays are a uint32 item type, a uint64 count and the items
 * (arrays of arrays included).
 */
object GgufMetadataReader {

    private const val MAGIC = 0x46554747 // "GGUF", little-endian
    private val SUPPORTED_VERSIONS = setOf(2, 3)

    /** Longest key or string value kept; anything longer is malformed or not worth holding (a chat template is a few KB). */
    private const val MAX_STRING_BYTES = 1L shl 20

    /**
     * [stopWhen] ends reading as soon as what was read so far is enough
     * (e.g. the architecture and context length are known), so a remote
     * check need not fetch a multi-megabyte vocabulary. Throws
     * [GgufFormatException] for anything that is not a readable GGUF
     * header, [EOFException] wrapped in it when the bytes end mid-header.
     */
    fun read(input: InputStream, stopWhen: (Map<String, Any>) -> Boolean = { false }): GgufMetadata {
        val data = DataInputStream(input.buffered())
        try {
            val magic = Integer.reverseBytes(data.readInt())
            if (magic != MAGIC) throw GgufFormatException("not a GGUF file (magic ${"%08x".format(magic)})")
            val version = Integer.reverseBytes(data.readInt())
            if (version !in SUPPORTED_VERSIONS) {
                throw GgufFormatException(
                    if (version and 0xFFFF == 0) "big-endian GGUF is not supported" else "unsupported GGUF version $version",
                )
            }
            val tensorCount = data.u64()
            val kvCount = data.u64()
            if (tensorCount < 0 || kvCount < 0) throw GgufFormatException("implausible counts: $tensorCount tensors, $kvCount keys")

            val values = linkedMapOf<String, Any>()
            val arrays = linkedMapOf<String, Long>()
            for (i in 0 until kvCount) {
                val key = data.string()
                val type = Integer.reverseBytes(data.readInt())
                if (type == TYPE_ARRAY) {
                    val itemType = Integer.reverseBytes(data.readInt())
                    val count = data.u64()
                    if (count < 0) throw GgufFormatException("$key: implausible array length $count")
                    repeat(count) { data.skipValue(itemType) }
                    arrays[key] = count
                } else {
                    values[key] = data.value(type)
                }
                if (stopWhen(values)) return GgufMetadata(version, tensorCount, values, arrays, complete = i == kvCount - 1)
            }
            return GgufMetadata(version, tensorCount, values, arrays, complete = true)
        } catch (e: EOFException) {
            throw GgufFormatException("the bytes end inside the GGUF header")
        }
    }

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private fun DataInputStream.u64(): Long = java.lang.Long.reverseBytes(readLong())

    private fun DataInputStream.string(): String {
        val length = u64()
        if (length < 0 || length > MAX_STRING_BYTES) throw GgufFormatException("implausible string length $length")
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.value(type: Int): Any = when (type) {
        TYPE_UINT8 -> readUnsignedByte().toLong()
        TYPE_INT8 -> readByte().toLong()
        TYPE_UINT16 -> (java.lang.Short.reverseBytes(readShort()).toInt() and 0xFFFF).toLong()
        TYPE_INT16 -> java.lang.Short.reverseBytes(readShort()).toLong()
        TYPE_UINT32 -> Integer.reverseBytes(readInt()).toLong() and 0xFFFFFFFFL
        TYPE_INT32 -> Integer.reverseBytes(readInt()).toLong()
        TYPE_FLOAT32 -> java.lang.Float.intBitsToFloat(Integer.reverseBytes(readInt())).toDouble()
        TYPE_BOOL -> readByte().toInt() != 0
        TYPE_STRING -> string()
        TYPE_UINT64, TYPE_INT64 -> u64()
        TYPE_FLOAT64 -> java.lang.Double.longBitsToDouble(u64())
        else -> throw GgufFormatException("unknown value type $type")
    }

    private fun DataInputStream.skipValue(type: Int) {
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> skipFully(1)
            TYPE_UINT16, TYPE_INT16 -> skipFully(2)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> skipFully(4)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> skipFully(8)
            TYPE_STRING -> {
                val length = u64()
                if (length < 0 || length > MAX_STRING_BYTES) throw GgufFormatException("implausible string length $length")
                skipFully(length)
            }
            TYPE_ARRAY -> {
                val itemType = Integer.reverseBytes(readInt())
                val count = u64()
                if (count < 0) throw GgufFormatException("implausible array length $count")
                repeat(count) { skipValue(itemType) }
            }
            else -> throw GgufFormatException("unknown value type $type")
        }
    }

    private fun DataInputStream.skipFully(bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private inline fun repeat(times: Long, action: () -> Unit) {
        var i = 0L
        while (i < times) {
            action()
            i++
        }
    }
}
