package ai.localstudio.model.install

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A minimal little-endian GGUF writer -- the layout of llama.cpp's gguf-py writer, header and metadata only. */
internal class GgufBytes(private val version: Int = 3, private val magic: Int = 0x46554747) {
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

    fun bytes(payload: Int = 64): ByteArray = u32(magic) + u32(version) + u64(7) + u64(count) + kv.toByteArray() + ByteArray(payload) // tensor infos, then weights
}

