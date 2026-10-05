package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.CandidateTier
import ai.localstudio.model.install.tier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CandidateTrialMarkerTest {

    private val dir = Files.createTempDirectory("trial-marker").toFile()
    private val marker = CandidateTrialMarker(File(dir, "sub/candidate_trial.json"))

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun running(loaded: Boolean = false) = RunningTrial("chat", "acme/a-GGUF", "Pixel / API 37", "llama_cpp", startedAtEpochMs = 1_000L, loaded = loaded)

    private val nativeCrash = ProcessDeath(2_000L, "native crash", "assertion \"!isnan(sumf)\" failed")

    @Test
    fun a_marker_survives_until_cleared() {
        marker.write(running())
        assertEquals(running(), CandidateTrialMarker(File(dir, "sub/candidate_trial.json")).read())
        marker.clear()
        assertNull(marker.read())
    }

    @Test
    fun a_crash_after_the_load_was_reported_is_loadable_with_the_crash_as_the_reason() {
        val v = CandidateTrialMarker.verdict(running(loaded = true), nativeCrash)!!
        assertEquals(CandidateTier.LOADABLE, v.tier())
        assertEquals("the app was killed during generation: native crash -- assertion \"!isnan(sumf)\" failed", v.error)
        assertEquals(2_000L, v.verifiedAtEpochMs)
        assertEquals("Pixel / API 37", v.deviceProfile)
    }

    @Test
    fun a_crash_before_the_load_was_reported_stays_unverified() {
        val v = CandidateTrialMarker.verdict(running(loaded = false), nativeCrash)!!
        assertEquals(CandidateTier.UNVERIFIED, v.tier())
        assertTrue(v.error!!.startsWith("the app was killed while loading"))
    }

    @Test
    fun no_notable_exit_or_one_older_than_the_test_proves_nothing() {
        assertNull(CandidateTrialMarker.verdict(running(), null))
        assertNull(CandidateTrialMarker.verdict(running(), nativeCrash.copy(atEpochMs = 999L)))
    }

    @Test
    fun work_tells_busy_candidates_apart() {
        val work = CandidateWork(
            downloads = mapOf("a" to CandidateDownload(1, 2)),
            trial = CandidateTrialState("b", CandidateTrialState.Phase.LOADING),
            queued = listOf("c"),
        )
        assertTrue(work.isBusy("a"))
        assertTrue(work.isBusy("b"))
        assertTrue(work.isBusy("c"))
        assertFalse(work.isBusy("d"))
        assertFalse(work.isIdle)
        assertTrue(CandidateWork(failures = mapOf("d" to "x")).isIdle)
        assertEquals(50, CandidateDownload(1, 2).percent)
        assertNull(CandidateDownload(1, 0).percent)
    }
}
