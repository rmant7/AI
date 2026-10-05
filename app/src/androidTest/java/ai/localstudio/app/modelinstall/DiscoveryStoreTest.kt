package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.DeviceVerification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** firstSeen set, so a stored run compares equal to what was recorded; the firstSeen tests pass 0 to see it filled in. */
    private fun candidate(id: String, firstSeen: Long = 1L) = DiscoveredCandidate(
        repoId = id, fileName = "model-Q4_K_M.gguf", filePath = "model-Q4_K_M.gguf", sizeBytes = 2_000_000_000,
        architecture = "qwen3", contextLength = 32768, notes = emptyList(),
        commit = "a".repeat(40), downloads = 1234, firstSeenAtEpochMs = firstSeen,
    )

    private fun verification(loaded: Boolean = true, inferenceOk: Boolean = true, at: Long = 5_000L) = DeviceVerification(
        deviceProfile = "Pixel 10 Pro / API 37 / 16.3 GB / llama.cpp b10448 (i8mm)",
        runtimeId = "llama_cpp", loaded = loaded, inferenceOk = inferenceOk, verifiedAtEpochMs = at,
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

    @Test
    fun recording_a_verification_touches_only_that_one_candidate() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 2, listOf(candidate("acme/a-GGUF"), candidate("acme/b-GGUF"))))

        assertTrue(store.recordVerification("chat", "acme/a-GGUF", verification()))

        val byId = store.runs().single().candidates.associateBy { it.repoId }
        assertEquals(verification(), byId.getValue("acme/a-GGUF").verification)
        assertNull("the other candidate is untouched", byId.getValue("acme/b-GGUF").verification)
    }

    @Test
    fun a_verification_survives_a_fresh_instance() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/a-GGUF"))))
        store.recordVerification("chat", "acme/a-GGUF", verification(loaded = true, inferenceOk = false))

        val reloaded = store().runs().single().candidates.single()
        assertEquals(false, reloaded.verification!!.inferenceOk)
    }

    @Test
    fun recording_a_verification_for_a_candidate_no_longer_there_is_told_apart() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/a-GGUF"))))

        assertFalse("wrong label", store.recordVerification("translation", "acme/a-GGUF", verification()))
        assertFalse("a sweep since replaced this run without that repo", store.recordVerification("chat", "acme/gone-GGUF", verification()))
    }

    @Test
    fun a_sweep_finding_the_same_file_again_keeps_its_verification() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/a-GGUF"))))
        store.recordVerification("chat", "acme/a-GGUF", verification())

        store.record(DiscoveryRun("chat", 2_000L, 2, listOf(candidate("acme/a-GGUF"), candidate("acme/b-GGUF"))))

        val byId = store.runs().single().candidates.associateBy { it.repoId }
        assertEquals(verification(), byId.getValue("acme/a-GGUF").verification)
        assertNull(byId.getValue("acme/b-GGUF").verification)
    }

    @Test
    fun a_new_commit_or_a_different_file_starts_unverified_again() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/a-GGUF"))))
        store.recordVerification("chat", "acme/a-GGUF", verification())

        store.record(DiscoveryRun("chat", 2_000L, 1, listOf(candidate("acme/a-GGUF").copy(commit = "c".repeat(40)))))
        assertNull("other bytes, no evidence about them", store.runs().single().candidates.single().verification)

        store.recordVerification("chat", "acme/a-GGUF", verification())
        store.record(DiscoveryRun("chat", 3_000L, 1, listOf(candidate("acme/a-GGUF").copy(commit = "c".repeat(40), filePath = "q8/model-Q8_0.gguf"))))
        assertNull(store.runs().single().candidates.single().verification)
    }

    @Test
    fun a_verification_after_the_last_look_counts_as_unseen() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/a-GGUF"))))
        store.markSeen()
        assertFalse(store.hasUnseen())

        store.recordVerification("chat", "acme/a-GGUF", verification(at = System.currentTimeMillis() + 10_000))
        assertTrue("a finished test is news even though the sweep was already seen", store.hasUnseen())
    }

    @Test
    fun a_candidate_keeps_what_the_search_said_about_it_and_its_full_path() {
        val outcome = ai.localstudio.model.install.ModelDiscovery.Outcome.Candidate(
            repo = ai.localstudio.model.install.RepoSummary(id = "acme/a-GGUF", downloads = 7, tags = listOf("gguf", "code", "license:mit")),
            commit = "a".repeat(40),
            file = ai.localstudio.model.install.RepoFile("Q4/a-Q4_K_M.gguf", 1_000, "b".repeat(64)),
            architecture = "qwen2",
            contextLength = 4096,
            notes = emptyList(),
        )
        val stored = store().apply { record(DiscoveryRun("chat", 1_000L, 1, listOf(DiscoveryStore.candidateOf(outcome)))) }
        val reloaded = store().runs().single().candidates.single()
        assertEquals(listOf("gguf", "code", "license:mit"), reloaded.tags)
        assertEquals("Q4/a-Q4_K_M.gguf", reloaded.filePath)
        assertEquals("a-Q4_K_M.gguf", reloaded.fileName)
        assertEquals("b".repeat(64), reloaded.sha256)
        assertTrue(stored.runs().isNotEmpty())
    }

    @Test
    fun a_result_stored_before_tags_were_kept_still_reads() {
        base.mkdirs()
        File(base, "discovery_results.json").writeText(
            """{"runs":[{"label":"chat","finishedAtEpochMs":1,"checked":1,"candidates":[{"repoId":"acme/a-GGUF","fileName":"a.gguf",""" +
                """"filePath":"a.gguf","sizeBytes":1,"architecture":"llama","contextLength":null,"notes":[],"commit":"c","downloads":0}]}]}""",
        )
        assertEquals(emptyList<String>(), store().runs().single().candidates.single().tags)
        assertNull("stored before projectors: a text-only model", store().runs().single().candidates.single().projector)
    }

    private fun projector(path: String = "mmproj-F16.gguf", commit: String = "a".repeat(40)) = ai.localstudio.model.install.ProjectorFile(
        ai.localstudio.model.install.ModelFile("acme/a-GGUF", commit, path, 800_000_000, "d".repeat(64)),
        "gemma3",
    )

    @Test
    fun a_projector_is_stored_as_part_of_its_model_not_as_a_candidate() {
        val outcome = ai.localstudio.model.install.ModelDiscovery.Outcome.Candidate(
            repo = ai.localstudio.model.install.RepoSummary(id = "acme/a-GGUF", downloads = 7),
            commit = "a".repeat(40),
            file = ai.localstudio.model.install.RepoFile("a-Q4_K_M.gguf", 2_000_000_000, "b".repeat(64)),
            architecture = "gemma3",
            contextLength = 4096,
            notes = emptyList(),
            projector = ai.localstudio.model.install.ModelDiscovery.Outcome.Projector(
                ai.localstudio.model.install.RepoFile("mmproj-F16.gguf", 800_000_000, "d".repeat(64)),
                "gemma3",
            ),
        )
        store().record(DiscoveryRun("chat", 1_000L, 1, listOf(DiscoveryStore.candidateOf(outcome))))

        val reloaded = store().runs().single().candidates.single()
        assertEquals(projector(), reloaded.projector)
        assertEquals(2_800_000_000, reloaded.totalBytes)
        assertEquals("a".repeat(40), reloaded.artifact().projector?.file?.revision)
        assertTrue(reloaded.artifact().canCheck(ai.localstudio.model.install.VerifiedCapability.VISION))
    }

    @Test
    fun the_same_pair_keeps_its_verification_and_a_new_or_different_projector_starts_over() {
        val store = store()
        val pair = candidate("acme/a-GGUF").copy(projector = projector())
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(pair)))
        store.recordVerification("chat", "acme/a-GGUF", verification(), pair.identity)

        store.record(DiscoveryRun("chat", 2_000L, 1, listOf(pair)))
        assertEquals(verification(), store.runs().single().candidates.single().verification)

        store.record(DiscoveryRun("chat", 3_000L, 1, listOf(candidate("acme/a-GGUF").copy(projector = projector("mmproj-Q8_0.gguf")))))
        assertNull("another projector: other bytes under test", store.runs().single().candidates.single().verification)

        store.recordVerification("chat", "acme/a-GGUF", verification(), candidate("acme/a-GGUF").identity)
        assertNull("a result about the text-only file does not land on the pair", store.runs().single().candidates.single().verification)
    }

    @Test
    fun a_text_only_verification_does_not_carry_to_the_same_file_found_with_a_projector() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/a-GGUF"))))
        store.recordVerification("chat", "acme/a-GGUF", verification())

        store.record(DiscoveryRun("chat", 2_000L, 1, listOf(candidate("acme/a-GGUF").copy(projector = projector()))))
        assertNull(store.runs().single().candidates.single().verification)
    }

    @Test
    fun a_verification_for_files_a_sweep_has_since_replaced_is_not_recorded() {
        val store = store()
        val tested = candidate("acme/a-GGUF")
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(tested.copy(commit = "c".repeat(40)))))

        assertFalse(store.recordVerification("chat", "acme/a-GGUF", verification(), tested.identity))
        assertNull(store.runs().single().candidates.single().verification)
    }

    @Test
    fun a_projector_from_another_commit_is_refused() {
        val failure = runCatching { candidate("acme/a-GGUF").copy(projector = projector(commit = "e".repeat(40))).artifact() }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun a_repository_found_again_keeps_when_it_was_first_found_even_at_a_new_commit() {
        val store = store()
        store.record(DiscoveryRun("chat:qwen", 1_000L, 1, listOf(candidate("acme/a-GGUF", firstSeen = 0))), nowMs = 1_000L)
        store.record(
            DiscoveryRun("chat:qwen", 2_000L, 2, listOf(candidate("acme/a-GGUF", firstSeen = 0).copy(commit = "c".repeat(40)), candidate("acme/b-GGUF", firstSeen = 0))),
            nowMs = 2_000L,
        )
        val byId = store.runs().single().candidates.associateBy { it.repoId }
        assertEquals(1_000L, byId.getValue("acme/a-GGUF").firstSeenAtEpochMs)
        assertEquals(2_000L, byId.getValue("acme/b-GGUF").firstSeenAtEpochMs)
    }

    @Test
    fun new_means_first_found_by_the_latest_sweep_and_nothing_is_new_on_the_very_first() {
        val store = store()
        store.beginSweep(nowMs = 1_000L)
        store.record(DiscoveryRun("chat:qwen", 1_500L, 1, listOf(candidate("acme/a-GGUF", firstSeen = 0))), nowMs = 1_500L)
        assertFalse("first sweep ever: nothing to compare with", store.isNew(store.runs().single().candidates.single()))

        store.beginSweep(nowMs = 5_000L)
        store.record(DiscoveryRun("chat:qwen", 5_500L, 2, listOf(candidate("acme/a-GGUF", firstSeen = 0), candidate("acme/b-GGUF", firstSeen = 0))), nowMs = 5_500L)
        val byId = store.runs().single().candidates.associateBy { it.repoId }
        assertFalse(store.isNew(byId.getValue("acme/a-GGUF")))
        assertTrue(store.isNew(byId.getValue("acme/b-GGUF")))
    }

    @Test
    fun a_complete_sweep_forgets_labels_it_no_longer_produces() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 1, listOf(candidate("acme/old-GGUF"))))
        store.record(DiscoveryRun("chat:qwen", 2_000L, 1, listOf(candidate("acme/a-GGUF"))))
        store.retainLabels(setOf("chat:qwen"))
        assertEquals(listOf("chat:qwen"), store.runs().map { it.label })
    }

    @Test
    fun labels_carry_purpose_and_family() {
        val qwen = ai.localstudio.model.install.Lineages.byId("qwen")!!
        assertEquals("chat:qwen", DiscoveryLabels.of(qwen))
        assertEquals("translation:hunyuan-mt", DiscoveryLabels.of(ai.localstudio.model.install.Lineages.byId("hunyuan-mt")!!))
        assertTrue(DiscoveryLabels.isTranslation("translation:hunyuan-mt"))
        assertTrue("a run stored before lineages", DiscoveryLabels.isTranslation("translation"))
        assertFalse(DiscoveryLabels.isTranslation("chat:qwen"))
        assertEquals(qwen, DiscoveryLabels.lineage("chat:qwen"))
        assertNull(DiscoveryLabels.lineage("chat"))
    }

    @Test
    fun news_is_what_happened_since_the_last_look() {
        val store = store()
        store.beginSweep(nowMs = 1_000L)
        store.record(DiscoveryRun("chat:qwen", 1_500L, 1, listOf(candidate("acme/a-GGUF", firstSeen = 0))), nowMs = 1_500L)
        store.markSeen()
        assertEquals(DiscoveryStore.News(emptyList(), emptyList(), sweepFinished = false), store.news())

        val later = System.currentTimeMillis() + 60_000
        store.beginSweep(nowMs = later)
        store.record(DiscoveryRun("chat:qwen", later + 1, 2, listOf(candidate("acme/a-GGUF", firstSeen = 0), candidate("acme/b-GGUF", firstSeen = 0))), nowMs = later + 1)
        store.recordVerification("chat:qwen", "acme/a-GGUF", verification(at = later + 2))

        val news = store.news()
        assertEquals(listOf("acme/b-GGUF"), news.newCandidates.map { it.second.repoId })
        assertEquals(listOf("acme/a-GGUF"), news.tested.map { it.second.repoId })
        assertTrue(news.sweepFinished)
        assertEquals(later, store.lastSweepAtEpochMs())
    }

    @Test
    fun a_repository_is_listed_once_the_latest_family_that_found_it_wins() {
        val store = store()
        store.record(DiscoveryRun("chat", 1_000L, 2, listOf(candidate("acme/a-GGUF"), candidate("acme/other-GGUF"))))

        // Found again by another family, now with its projector.
        store.record(DiscoveryRun("chat:qwen", 2_000L, 1, listOf(candidate("acme/a-GGUF").copy(projector = projector(path = "mmproj-F16.gguf")))))

        val byLabel = store.runs().associate { it.label to it.candidates.map { c -> c.repoId } }
        assertEquals(listOf("acme/other-GGUF"), byLabel.getValue("chat"))
        assertEquals(listOf("acme/a-GGUF"), byLabel.getValue("chat:qwen"))
        assertEquals(1, store.runs().flatMap { it.candidates }.count { it.repoId == "acme/a-GGUF" })
    }
}
