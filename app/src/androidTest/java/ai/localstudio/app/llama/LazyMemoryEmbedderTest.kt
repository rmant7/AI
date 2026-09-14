package ai.localstudio.app.llama

import ai.localstudio.memory.MemoryEmbedder
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LazyMemoryEmbedder] has no Android dependency at all — this lives in
 * androidTest rather than a JVM unit test only because that's the one test
 * source set this module's CI (`connectedDebugAndroidTest`, see
 * `.github/workflows/android.yml`) already runs; there was no existing
 * `:app` JVM unit-test wiring to add instead without also touching CI.
 *
 * Exercises exactly what [AppContainer]'s own automatic-download-and-load
 * task depends on: every call before [LazyMemoryEmbedder.set] degrades to
 * empty results (the same "no embedder configured" fallback
 * `ai.localstudio.memory.SemanticRetrieval.candidates` already treats as
 * lexical-only), every call after it delegates correctly, and the Memory
 * screen's own "Semantic retrieval" switch (wired through the `isEnabled`
 * constructor parameter) degrades the same way as "not ready yet" even
 * once a real embedder is set — instantly, on the next call, with no
 * re-[set] needed.
 *
 * Also exercises the full memory-pressure lifecycle this class backs —
 * downloaded/loaded/semantic-works, [unload] freeing native resources
 * (via a fake, since the real native handle needs a device), an immediate
 * post-unload call staying non-blocking and lexical-only while
 * `reloadTrigger` fires exactly once in the background, and the next call
 * after that reload lands using the real embedder again — the same
 * sequence [AppContainer]'s real `ComponentCallbacks2`/`reloadTrigger`
 * wiring runs on an actual device under actual memory pressure, minus the
 * one part no test can force: Android itself deciding to deliver
 * TRIM_MEMORY_RUNNING_LOW. `AppContainer.simulateMemoryPressureForTesting`
 * is the diagnostic seam for exercising that specific wiring for real, on
 * a device, without waiting for genuine memory pressure.
 */
@RunWith(AndroidJUnit4::class)
class LazyMemoryEmbedderTest {

    private class FakeEmbedder(
        override val modelId: String,
        override val dimension: Int,
        private val queryVector: FloatArray,
        private val storageVectors: List<FloatArray>,
    ) : MemoryEmbedder {
        override suspend fun embedForQuery(query: String): FloatArray = queryVector
        override suspend fun embedForStorage(texts: List<String>): List<FloatArray> = storageVectors
    }

    @Test
    fun before_set_reports_pending_placeholders_and_empty_vectors() {
        val lazy = LazyMemoryEmbedder()

        assertFalse(lazy.isReady)
        assertEquals("pending", lazy.modelId)
        assertEquals(0, lazy.dimension)

        runBlocking {
            assertArrayEquals(FloatArray(0), lazy.embedForQuery("does this app work?"), 0f)
            assertTrue(lazy.embedForStorage(listOf("a fact", "another fact")).isEmpty())
        }
    }

    @Test
    fun after_set_delegates_every_call_to_the_real_embedder() {
        val lazy = LazyMemoryEmbedder()
        val queryVector = floatArrayOf(0.1f, 0.2f, 0.3f)
        val storageVectors = listOf(floatArrayOf(0.4f, 0.5f, 0.6f))
        val real = FakeEmbedder(modelId = "multilingual-e5-base-q4km", dimension = 768, queryVector, storageVectors)

        runBlocking { lazy.set(real) }

        assertTrue(lazy.isReady)
        assertEquals("multilingual-e5-base-q4km", lazy.modelId)
        assertEquals(768, lazy.dimension)

        runBlocking {
            assertArrayEquals(queryVector, lazy.embedForQuery("any query"), 0f)
            assertEquals(storageVectors, lazy.embedForStorage(listOf("any text")))
        }
    }

    @Test
    fun disabled_degrades_to_empty_even_with_a_real_embedder_set() {
        var enabled = true
        val lazy = LazyMemoryEmbedder(isEnabled = { enabled })
        val real = FakeEmbedder(
            modelId = "multilingual-e5-base-q4km",
            dimension = 768,
            queryVector = floatArrayOf(0.1f, 0.2f, 0.3f),
            storageVectors = listOf(floatArrayOf(0.4f, 0.5f, 0.6f)),
        )
        runBlocking { lazy.set(real) }
        enabled = false

        // isReady still reports the real model as loaded — turning semantic
        // retrieval off is not the same question as whether the model is
        // resident, see the class's own doc comment.
        assertTrue(lazy.isReady)

        runBlocking {
            assertArrayEquals(FloatArray(0), lazy.embedForQuery("any query"), 0f)
            assertTrue(lazy.embedForStorage(listOf("any text")).isEmpty())
        }

        // Flipping back takes effect on the very next call, no re-set().
        enabled = true
        runBlocking {
            assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), lazy.embedForQuery("any query"), 0f)
        }
    }

    @Test
    fun unload_frees_the_delegate_runs_release_once_and_degrades_like_before_set() {
        val lazy = LazyMemoryEmbedder()
        val real = FakeEmbedder(
            modelId = "multilingual-e5-base-q4km",
            dimension = 768,
            queryVector = floatArrayOf(0.1f, 0.2f, 0.3f),
            storageVectors = listOf(floatArrayOf(0.4f, 0.5f, 0.6f)),
        )
        var releaseCount = 0

        runBlocking {
            lazy.set(real) { releaseCount++ }
            assertTrue(lazy.isReady)

            lazy.unload()
        }

        assertEquals(1, releaseCount)
        assertFalse(lazy.isReady)
        assertEquals("pending", lazy.modelId)
        assertEquals(0, lazy.dimension)

        runBlocking {
            assertArrayEquals(FloatArray(0), lazy.embedForQuery("any query"), 0f)
            assertTrue(lazy.embedForStorage(listOf("any text")).isEmpty())

            // A no-op when nothing is loaded — AppContainer's onTrimMemory
            // callback always calls this guarded by isReady, but unload()
            // itself must stay safe even if that guard were ever dropped.
            lazy.unload()
        }
        assertEquals(1, releaseCount)
    }

    @Test
    fun set_after_unload_reloads_and_delegates_again() {
        val lazy = LazyMemoryEmbedder()
        val first = FakeEmbedder(
            modelId = "first",
            dimension = 768,
            queryVector = floatArrayOf(0.1f, 0.2f, 0.3f),
            storageVectors = emptyList(),
        )
        val second = FakeEmbedder(
            modelId = "second",
            dimension = 768,
            queryVector = floatArrayOf(0.7f, 0.8f, 0.9f),
            storageVectors = emptyList(),
        )

        runBlocking {
            lazy.set(first)
            lazy.unload()
            lazy.set(second)
        }

        assertTrue(lazy.isReady)
        assertEquals("second", lazy.modelId)
        runBlocking {
            assertArrayEquals(floatArrayOf(0.7f, 0.8f, 0.9f), lazy.embedForQuery("any query"), 0f)
        }
    }

    @Test
    fun unload_triggers_a_single_async_reload_that_activates_once_it_completes() {
        var triggerCalls = 0
        var pendingOnComplete: (() -> Unit)? = null
        val lazy = LazyMemoryEmbedder(
            reloadTrigger = { onComplete ->
                triggerCalls++
                // A real AppContainer fires this on its own background
                // coroutine (see ensureEmbedderLoaded's own doc comment) —
                // capturing onComplete here instead of invoking it inline
                // is what lets this test control exactly when the
                // "reload" finishes, the same way that background
                // coroutine's own completion would.
                pendingOnComplete = onComplete
            },
        )
        val original = FakeEmbedder(
            modelId = "original",
            dimension = 768,
            queryVector = floatArrayOf(0.1f, 0.1f, 0.1f),
            storageVectors = emptyList(),
        )
        val reloaded = FakeEmbedder(
            modelId = "reloaded",
            dimension = 768,
            queryVector = floatArrayOf(0.9f, 0.9f, 0.9f),
            storageVectors = emptyList(),
        )

        runBlocking {
            lazy.set(original)
            lazy.unload()

            // The very first request after unload must not block on the
            // reload — it degrades to lexical-only immediately, exactly
            // like any other not-ready call, while reloadTrigger runs
            // whatever load it kicked off in the background.
            assertArrayEquals(FloatArray(0), lazy.embedForQuery("q1"), 0f)
            assertEquals(1, triggerCalls)

            // A second request arriving before that reload finishes must
            // not start a second, concurrent one.
            assertArrayEquals(FloatArray(0), lazy.embedForQuery("q2"), 0f)
            assertFalse(lazy.isReady)
            assertEquals(1, triggerCalls)

            // The reload "completes" — a real AppContainer's
            // ensureEmbedderLoaded calls set() with the model loaded
            // straight from the file already on disk, no re-download,
            // then runs the reloadTrigger's own completion callback.
            lazy.set(reloaded)
            pendingOnComplete?.invoke()

            // The very next call goes back to real semantic retrieval.
            assertArrayEquals(floatArrayOf(0.9f, 0.9f, 0.9f), lazy.embedForQuery("q3"), 0f)

            // A later unload/not-ready cycle can trigger a fresh reload —
            // the dedup guard does not permanently latch after firing once.
            lazy.unload()
            assertArrayEquals(FloatArray(0), lazy.embedForQuery("q4"), 0f)
            assertEquals(2, triggerCalls)
        }
    }

    @Test
    fun set_called_again_without_unload_releases_the_previous_delegate_exactly_once() {
        val lazy = LazyMemoryEmbedder()
        var firstReleaseCount = 0
        var secondReleaseCount = 0
        val first = FakeEmbedder(
            modelId = "first",
            dimension = 768,
            queryVector = floatArrayOf(0.1f, 0.1f, 0.1f),
            storageVectors = emptyList(),
        )
        val second = FakeEmbedder(
            modelId = "second",
            dimension = 768,
            queryVector = floatArrayOf(0.2f, 0.2f, 0.2f),
            storageVectors = emptyList(),
        )

        runBlocking {
            lazy.set(first) { firstReleaseCount++ }
            assertTrue(lazy.isReady)
            assertEquals("first", lazy.modelId)

            // No unload() between these two set() calls — this is what a
            // completed background reload landing right as the periodic
            // backfill loop's own load also just finished would look like.
            lazy.set(second) { secondReleaseCount++ }

            assertEquals(1, firstReleaseCount)
            assertEquals(0, secondReleaseCount)
            assertTrue(lazy.isReady)
            assertEquals("second", lazy.modelId)
            assertArrayEquals(floatArrayOf(0.2f, 0.2f, 0.2f), lazy.embedForQuery("q"), 0f)

            lazy.unload()
            assertEquals(1, secondReleaseCount)
        }
    }
}
