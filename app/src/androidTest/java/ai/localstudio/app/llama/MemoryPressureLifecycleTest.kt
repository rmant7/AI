package ai.localstudio.app.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level regression test for the exact lifecycle
 * [ai.localstudio.app.AppContainer]'s real memory-pressure wiring runs on a
 * real phone: E5 downloaded → loaded → semantic search works → a real native
 * unload frees its resources → a search issued immediately after that does
 * not block or crash, degrading to lexical-only instead → an async reload
 * fires in the background, from the same on-disk file, never a re-download →
 * the next search after that lands back on the real model.
 *
 * Manual, on-device verification only, same convention as the sibling
 * [ExperimentalEmbeddingModelTest]: [assumeTrue] skips this (does not fail
 * it) whenever no real E5_BASE model file has been manually placed, which is
 * the case for every CI run — CI has no network to download roughly 200 MB,
 * and downloading it fresh on every run would be slow and wasteful even if
 * it did. This uses [LazyMemoryEmbedder] and two real
 * [LlamaCppMemoryEmbedder] loads directly rather than going through
 * [ai.localstudio.app.AppContainer]'s own process-wide singleton — that
 * singleton's background tasks (auto-download, periodic backfill, its own
 * `ComponentCallbacks2` registration) are shared with whichever other
 * instrumented test happens to run in the same process and would race this
 * test's manual load/unload sequence in ways this class has no control over.
 * [ai.localstudio.app.AppContainer.simulateMemoryPressureForTesting] is the
 * seam for exercising that specific singleton wiring for real, on a device,
 * without waiting for genuine memory pressure — this class instead proves
 * the lifecycle contract [LazyMemoryEmbedder] itself guarantees, with real
 * native loads standing in for [ai.localstudio.app.AppContainer]'s own.
 *
 * To actually run this:
 * 1. Download the E5 Base GGUF — see [ExperimentalEmbeddingModels.E5_BASE]'s
 *    own doc comment for the exact Hugging Face repo.
 * 2. `adb push e5-base.gguf /sdcard/Android/data/<applicationId>/files/experimental_embeddings/multilingual-e5-base-q4km.gguf`
 *    (create the `experimental_embeddings` directory first if needed) — the
 *    exact path [ExperimentalEmbeddingStore] uses for a real download, so
 *    this test's load exercises the identical file location production code
 *    depends on.
 * 3. `./gradlew connectedDebugAndroidTest --tests "*MemoryPressureLifecycleTest*"`
 */
@RunWith(AndroidJUnit4::class)
class MemoryPressureLifecycleTest {

    @Test
    fun downloaded_loaded_unloaded_under_pressure_and_reloaded_lifecycle() = runBlocking {
        assumeTrue("llama_jni did not load for this ABI", LlamaBridge.isAvailable)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ExperimentalEmbeddingStore(context)
        val spec = ExperimentalEmbeddingModels.E5_BASE

        // Step 1: E5 downloaded.
        assumeTrue(
            "no E5_BASE model at ${store.fileFor(spec).absolutePath} - see this class's own doc comment for how to place one",
            store.isInstalled(spec),
        )

        var reloadCalls = 0
        lateinit var lazy: LazyMemoryEmbedder
        lazy = LazyMemoryEmbedder(
            reloadTrigger = { onComplete ->
                // Counted synchronously, on whatever thread requestReload()
                // itself runs on — not inside the launched coroutine below,
                // whose body can start running an arbitrary delay after
                // launch() returns and would otherwise race the assertion
                // right after the triggering embedForQuery call returns.
                reloadCalls++
                // Mirrors AppContainer.ensureEmbedderLoaded exactly: a fresh
                // load from the same on-disk file, never a re-download.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val reloaded = LlamaCppMemoryEmbedder.load(
                            bridge = LlamaBridge(),
                            modelPath = store.fileFor(spec).absolutePath,
                            modelId = spec.id,
                            pooling = spec.pooling,
                            queryPrefix = spec.queryPrefix,
                            passagePrefix = spec.passagePrefix,
                        )
                        if (reloaded != null) {
                            lazy.set(reloaded) { reloaded.close() }
                        }
                    } finally {
                        onComplete()
                    }
                }
            },
        )

        // Step 2: E5 loaded.
        val embedder = LlamaCppMemoryEmbedder.load(
            bridge = LlamaBridge(),
            modelPath = store.fileFor(spec).absolutePath,
            modelId = spec.id,
            pooling = spec.pooling,
            queryPrefix = spec.queryPrefix,
            passagePrefix = spec.passagePrefix,
        )
        assertTrue("model failed to load from ${store.fileFor(spec).absolutePath}", embedder != null)
        requireNotNull(embedder)
        lazy.set(embedder) { embedder.close() }
        assertTrue(lazy.isReady)

        try {
            // Step 3: semantic retrieval works.
            val beforeVector = lazy.embedForQuery("рецепты низкокалорийных десертов")
            assertEquals("expected a real vector before unload", spec.dimension, beforeVector.size)

            // Step 4/5: simulate TRIM_MEMORY_RUNNING_LOW — a real native
            // unload, freeing the embedding context this same test loaded
            // above (AppContainer's own onTrimMemory callback runs this
            // exact LazyMemoryEmbedder.unload() call in production; see
            // AppContainer.simulateMemoryPressureForTesting for exercising
            // that specific callback wiring on a real device).
            lazy.unload()
            assertFalse(lazy.isReady)

            // Step 6/7: a semantic search issued immediately after unload
            // must not crash or hang — it degrades to the same lexical-only
            // empty vector as before the very first set(), and returns well
            // within a couple of seconds, proving it did not block waiting
            // on the reload a real GGUF load would otherwise take.
            val duringReload = withTimeout(2_000) { lazy.embedForQuery("любой запрос") }
            assertArrayEquals(FloatArray(0), duringReload, 0f)
            assertEquals(1, reloadCalls)

            // Step 8/9: the async reload lands from the same local file —
            // nothing above ever calls a downloader — and the next search
            // after that uses the real model again.
            withTimeout(30_000) {
                while (!lazy.isReady) {
                    delay(50)
                }
            }
            val afterReload = lazy.embedForQuery("рецепты низкокалорийных десертов")
            assertEquals("expected a real vector after the async reload completed", spec.dimension, afterReload.size)
        } finally {
            // Whichever delegate ended up installed (the original load, or
            // the reload) is released via its own `set`-provided callback.
            lazy.unload()
        }
    }
}
