package ai.localstudio.app.modelinstall

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [DiscoveryStore] survives the exact gap a device report caught: a sweep
 * finishes after the person has already left the Models screen (or closed
 * the app), so nothing ties the result to that one screen's lifecycleScope
 * any more. These tests use a scratch directory, not the real app's
 * filesDir, so they never touch a real discovery result on the device.
 */
@RunWith(AndroidJUnit4::class)
class DiscoveryStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "discovery-store-${System.nanoTime()}")

    private fun store() = DiscoveryStore(context, baseDir = base)

    private fun candidate(id: String) = DiscoveredCandidate(
        repoId = id, fileName = "model-Q4_K_M.gguf", sizeBytes = 2_000_000_000,
        architecture = "qwen3", contextLength = 32768, notes = emptyList(),
        commit = "a".repeat(40), downloads = 1234,
    )

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun a_run_survives_a_fresh_instance_the_same_as_a_process_restart_would() {
        val run = DiscoveryRun("chat", finishedAtEpochMs = 1_000L, checked = 5, candidates = listOf(candidate("acme/a-GGUF")))
        store().record(run)

        val fresh = store()
        assertEquals(listOf(run), fresh.runs())
        assertTrue("a run nobody has looked at yet", fresh.hasUnseen())
    }

    @Test
    fun recording_a_label_again_replaces_only_that_labels_run() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 5, listOf(candidate("acme/a-GGUF"))))
        store.record(DiscoveryRun("translation", 1_000L, 3, listOf(candidate("acme/b-GGUF"))))
        store.record(DiscoveryRun("chat", 2_000L, 7, listOf(candidate("acme/c-GGUF"))))

        assertEquals("one run per label, not one per record() call", 2, store.runs().size)
        val byLabel = store.runs().associateBy { it.label }
        assertEquals(2, byLabel.size)
        assertEquals(2_000L, byLabel.getValue("chat").finishedAtEpochMs)
        assertEquals("acme/c-GGUF", byLabel.getValue("chat").candidates.single().repoId)
        assertEquals(1_000L, byLabel.getValue("translation").finishedAtEpochMs)
    }

    @Test
    fun marking_seen_is_not_fooled_by_an_older_run_still_on_disk() {
        val store = store()
        store.record(DiscoveryRun("chat", finishedAtEpochMs = System.currentTimeMillis(), checked = 1, candidates = emptyList()))
        assertTrue(store.hasUnseen())
        store.markSeen()
        assertFalse("just shown, nothing new since", store.hasUnseen())

        // A second label finishing later is new, even though "chat" was already seen.
        store.record(DiscoveryRun("translation", finishedAtEpochMs = System.currentTimeMillis() + 10_000, checked = 1, candidates = emptyList()))
        assertTrue(store.hasUnseen())
    }

    @Test
    fun a_failed_run_is_recorded_with_its_reason_and_no_candidates() {
        val store = store()
        store.record(DiscoveryRun("chat", finishedAtEpochMs = 1_000L, checked = 0, candidates = emptyList(), failure = "IOException: timeout"))
        val run = store.runs().single()
        assertEquals("IOException: timeout", run.failure)
        assertEquals(0, run.checked)
        assertTrue(run.candidates.isEmpty())
    }

    @Test
    fun an_empty_store_has_nothing_unseen() {
        assertFalse(store().hasUnseen())
        assertTrue(store().runs().isEmpty())
    }
}
