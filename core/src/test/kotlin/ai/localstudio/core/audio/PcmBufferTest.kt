package ai.localstudio.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmBufferTest {

    @Test
    fun `take returns samples in FIFO order`() {
        val buffer = PcmBuffer()
        buffer.append(shortArrayOf(1, 2, 3))
        buffer.append(shortArrayOf(4, 5))

        assertEquals(5, buffer.size)
        assertEquals(listOf<Short>(1, 2, 3), buffer.take(3).toList())
        assertEquals(2, buffer.size)
        assertEquals(listOf<Short>(4, 5), buffer.take(10).toList())
        assertEquals(0, buffer.size)
    }

    @Test
    fun `takeAll drains everything buffered`() {
        val buffer = PcmBuffer()
        buffer.append(ShortArray(100) { it.toShort() })
        val all = buffer.takeAll()
        assertEquals(100, all.size)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `grows past its initial capacity without losing data`() {
        val buffer = PcmBuffer()
        val big = ShortArray(1 shl 18) { (it % 30_000).toShort() }
        buffer.append(big)
        assertEquals(big.toList(), buffer.takeAll().toList())
    }

    @Test
    fun `interleaved append and partial take keeps order across many cycles`() {
        val buffer = PcmBuffer()
        val expected = mutableListOf<Short>()
        val produced = mutableListOf<Short>()
        repeat(50) { cycle ->
            val block = ShortArray(1_000) { (cycle * 1_000 + it).toShort() }
            buffer.append(block)
            expected += block.toList()
            if (cycle % 3 == 0) produced += buffer.take(700).toList()
        }
        produced += buffer.takeAll().toList()
        assertEquals(expected, produced)
    }

    @Test
    fun `take on an empty buffer returns nothing`() {
        assertTrue(PcmBuffer().take(10).isEmpty())
    }
}
