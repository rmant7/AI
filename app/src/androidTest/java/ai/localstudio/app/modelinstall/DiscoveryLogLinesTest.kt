package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.ModelDiscovery
import ai.localstudio.model.install.RepoFile
import ai.localstudio.model.install.RepoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLogLinesTest {

    private fun drop(repo: String, reason: String) = ModelDiscovery.Outcome.Dropped(RepoSummary(repo), reason)

    @Test
    fun drops_are_one_line_counted_by_reason_with_the_unusual_ones_spelled_out() {
        val drops = List(10) { drop("a/big-$it-30B-GGUF", "~30B parameters by its name: too large for this device, not examined") } +
            List(25) { drop("b/dup-$it-GGUF", "same model as x/orig-GGUF") } +
            drop("c/img-GGUF", "not a text-generation model (text-to-image)") +
            drop("d/odd-GGUF", "no GGUF file at all")
        val line = DiscoveryLogLines.dropped("chat:qwen", drops)!!
        assertEquals(1, line.lines().size)
        assertTrue(line, line.startsWith("chat:qwen dropped 37 (duplicate 25, too large 10, "))
        assertTrue(line, "d/odd-GGUF: no GGUF file at all" in line)
        assertTrue("the common reasons are counted, not listed", "a/big-0" !in line && "b/dup-0" !in line)
        assertNull(DiscoveryLogLines.dropped("chat:qwen", emptyList()))
    }

    @Test
    fun a_candidate_line_says_whether_it_can_see() {
        fun candidate(projector: ModelDiscovery.Outcome.Projector?, notes: List<String>) = ModelDiscovery.Outcome.Candidate(
            repo = RepoSummary("unsloth/gemma-3-4b-it-GGUF", downloads = 400_000),
            commit = "a".repeat(40),
            file = RepoFile("gemma-3-4b-it-Q4_K_M.gguf", 2_489_000_000, null),
            architecture = "gemma3", contextLength = 131072, notes = notes, projector = projector,
        )
        val seeing = DiscoveryLogLines.candidate("chat:gemma", candidate(ModelDiscovery.Outcome.Projector(RepoFile("mmproj-F16.gguf", 851_000_000, null), "gemma3"), emptyList()))
        assertTrue(seeing, "vision gemma3 +851 MB" in seeing)
        val blind = DiscoveryLogLines.candidate("chat:gemma", candidate(null, listOf("projector mmproj-F16.gguf left out: no projector type in the header", "no chat template")))
        assertTrue(blind, "no vision: projector mmproj-F16.gguf left out" in blind && "1 note(s)" in blind)
    }
}
