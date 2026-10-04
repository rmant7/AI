package ai.localstudio.model.install

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal little-endian GGUF writer -- the layout of llama.cpp's gguf-py writer, header and metadata only. */
private class GgufBytes(private val version: Int = 3, private val magic: Int = 0x46554747) {
    private val kv = ByteArrayOutputStream()
    private var count = 0L

    private fun le(size: Int, put: ByteBuffer.() -> Unit) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(put).array()
    private fun u32(v: Int) = le(4) { putInt(v) }
    private fun u64(v: Long) = le(8) { putLong(v) }
    private fun str(s: String) = s.toByteArray().let { u64(it.size.toLong()) + it }

    private fun entry(key: String, type: Int, value: ByteArray) = apply {
        kv.write(str(key)); kv.write(u32(type)); kv.write(value); count++
    }

    fun string(key: String, value: String) = entry(key, 8, str(value))
    fun u32(key: String, value: Int) = entry(key, 4, u32(value))
    fun u64(key: String, value: Long) = entry(key, 10, u64(value))
    fun f32(key: String, value: Float) = entry(key, 6, le(4) { putFloat(value) })
    fun bool(key: String, value: Boolean) = entry(key, 7, byteArrayOf(if (value) 1 else 0))
    fun strings(key: String, items: List<String>) = entry(key, 9, u32(8) + u64(items.size.toLong()) + items.flatMap { str(it).toList() }.toByteArray())
    fun nested(key: String) = entry(key, 9, u32(9) + u64(2) + (u32(5) + u64(3) + ByteArray(12)) + (u32(8) + u64(1) + str("x")))

    fun bytes(): ByteArray = u32(magic) + u32(version) + u64(7) + u64(count) + kv.toByteArray() + ByteArray(64) // tensor infos follow
}

class GgufMetadataTest {

    private val gemma = GgufBytes()
        .string("general.architecture", "gemma3")
        .string("general.name", "Gemma 3 4b It")
        .string("general.size_label", "4B")
        .u32("general.file_type", 15)
        .u32("gemma3.context_length", 131072)
        .f32("gemma3.rope.freq_base", 1_000_000f)
        .bool("tokenizer.ggml.add_bos_token", true)
        .strings("tokenizer.ggml.tokens", List(1000) { "tok$it" })
        .nested("test.nested")
        .string("tokenizer.chat_template", "{{ bos_token }}{% for m in messages %}...{% endfor %}")
        .u64("general.quantization_version", 2)

    @Test
    fun reads_the_keys_a_compatibility_check_needs_and_skips_arrays() {
        val meta = GgufMetadataReader.read(gemma.bytes().inputStream())
        assertEquals(3, meta.version)
        assertEquals(7, meta.tensorCount)
        assertEquals("gemma3", meta.architecture)
        assertEquals("Gemma 3 4b It", meta.name)
        assertEquals("4B", meta.sizeLabel)
        assertEquals(15, meta.fileType)
        assertEquals(131072, meta.contextLength)
        assertTrue(meta.hasChatTemplate)
        assertEquals(1000, meta.vocabularySize)
        assertEquals(2L, meta.arrayLengths["test.nested"])
        assertEquals(true, meta.values["tokenizer.ggml.add_bos_token"])
        assertEquals(1_000_000.0, meta.values["gemma3.rope.freq_base"])
        assertEquals(2L, meta.values["general.quantization_version"])
        assertTrue(meta.complete)
    }

    @Test
    fun stops_as_soon_as_it_has_enough() {
        val meta = GgufMetadataReader.read(gemma.bytes().inputStream()) { "gemma3.context_length" in it }
        assertEquals("gemma3", meta.architecture)
        assertEquals(131072, meta.contextLength)
        assertFalse(meta.complete)
        assertNull(meta.vocabularySize)
        assertFalse(meta.hasChatTemplate, "not read yet, not absent")
    }

    @Test
    fun stopping_early_needs_only_the_first_bytes() {
        val all = gemma.bytes()
        val prefix = all.copyOf(400)
        val meta = GgufMetadataReader.read(prefix.inputStream()) { "gemma3.context_length" in it }
        assertEquals(131072, meta.contextLength)
        assertFailsWith<GgufFormatException> { GgufMetadataReader.read(prefix.inputStream()) }
    }

    @Test
    fun anything_else_is_a_format_error() {
        assertFailsWith<GgufFormatException> { GgufMetadataReader.read(GgufBytes(magic = 0x0A0D4B50).bytes().inputStream()) }
        assertFailsWith<GgufFormatException> { GgufMetadataReader.read(GgufBytes(version = 1).bytes().inputStream()) }
        val bigEndian = assertFailsWith<GgufFormatException> { GgufMetadataReader.read(GgufBytes(version = 3 shl 24).bytes().inputStream()) }
        assertTrue(bigEndian.message!!.contains("big-endian"))
        assertFailsWith<GgufFormatException> { GgufMetadataReader.read(ByteArray(3).inputStream()) }
        // A string claiming more bytes than any real key or template has.
        val huge = GgufBytes().bytes().let { it.copyOf(24) } +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L shl 40).array()
        val hugeHeader = huge.also { ByteBuffer.wrap(it, 16, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1) }
        assertFailsWith<GgufFormatException> { GgufMetadataReader.read(hugeHeader.inputStream()) }
    }

    @Test
    fun compatibility_follows_the_bundled_architectures() {
        val meta = GgufMetadataReader.read(gemma.bytes().inputStream())
        assertEquals(GgufCompatibility.Loadable("gemma3", 131072, emptyList()), GgufCompatibility.of(meta))

        val future = GgufMetadataReader.read(GgufBytes().string("general.architecture", "gemma9").bytes().inputStream())
        val refused = assertIs<GgufCompatibility.NotLoadable>(GgufCompatibility.of(future))
        assertTrue(refused.reason.contains("gemma9") && refused.reason.contains(LlamaCppArchitectures.LLAMA_CPP_TAG))

        val bare = GgufMetadataReader.read(GgufBytes().string("general.architecture", "llama").bytes().inputStream())
        assertEquals(listOf("no llama.context_length", "no chat template"), (GgufCompatibility.of(bare) as GgufCompatibility.Loadable).notes)

        assertIs<GgufCompatibility.NotLoadable>(GgufCompatibility.of(GgufMetadataReader.read(GgufBytes().bytes().inputStream())))
    }
}
