package ai.localstudio.core.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiKeyRotatorTest {

    private var now = 0L
    private val store = InMemoryApiKeyStore()
    private val rotator = ApiKeyRotator(store, "gemini", clock = { now })

    @Test
    fun `an empty pool has no active key and is not reported as exhausted`() {
        assertNull(rotator.activeKey())
        assertNull(rotator.exhaustionMessage())
    }

    @Test
    fun `the first added key is the active one`() {
        val a = rotator.add("key-a")
        rotator.add("key-b")

        assertEquals(a, rotator.activeKey())
    }

    @Test
    fun `marking a key exhausted rotates to the next one automatically`() {
        val a = rotator.add("key-a")
        val b = rotator.add("key-b")

        rotator.markExhausted(a.id)

        assertEquals(b, rotator.activeKey())
    }

    @Test
    fun `an exhausted key is not retried within 24 hours`() {
        val a = rotator.add("key-a")
        rotator.add("key-b")
        rotator.markExhausted(a.id)

        now += 23 * 60 * 60 * 1000L
        assertTrue(rotator.activeKey()?.id != a.id)
    }

    @Test
    fun `an exhausted key becomes usable again after its cooldown expires`() {
        val a = rotator.add("key-a")
        val b = rotator.add("key-b")
        rotator.markExhausted(a.id)
        rotator.markExhausted(b.id)
        assertNull(rotator.activeKey())

        now += ApiKeyRotator.DEFAULT_COOLDOWN_MS + 1

        // Not assertEquals(a, ...): the stored entry now carries the
        // cooldownUntilEpochMs set by markExhausted, so it no longer equals
        // the original snapshot even though it is once again the active key.
        assertEquals(a.id, rotator.activeKey()?.id)
    }

    @Test
    fun `once every key is exhausted, activeKey is null and the reason is reported`() {
        val a = rotator.add("key-a")
        val b = rotator.add("key-b")
        rotator.markExhausted(a.id)
        rotator.markExhausted(b.id)

        assertNull(rotator.activeKey())
        val message = assertNotNull(rotator.exhaustionMessage())
        assertTrue(message.contains("2"))
    }

    @Test
    fun `removing a key drops it from the pool and from rotation`() {
        val a = rotator.add("key-a")
        val b = rotator.add("key-b")

        rotator.remove(a.id)

        assertEquals(listOf(b), rotator.pool())
        assertEquals(b, rotator.activeKey())
    }

    @Test
    fun `pools for different providers do not interfere`() {
        rotator.add("gemini-key")
        val mistral = ApiKeyRotator(store, "mistral", clock = { now })

        assertEquals(1, rotator.poolSize())
        assertEquals(0, mistral.poolSize())
    }
}
