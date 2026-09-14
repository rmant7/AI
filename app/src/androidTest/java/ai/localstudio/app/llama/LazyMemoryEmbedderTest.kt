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

        lazy.set(real)

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
        lazy.set(real)
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
}
