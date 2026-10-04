package ai.localstudio.model.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelDiscoveryTest {

    private val hub = FakeHuggingFace()
    private val transport = FakeTransport()
    private val found = mutableListOf<RepoSummary>()
    private val search = object : HuggingFaceSearch {
        var lastQuery: ModelSearchQuery? = null
        override fun searchModels(query: ModelSearchQuery): List<RepoSummary> = found.also { lastQuery = query }
    }
    private val discovery = ModelDiscovery(search, hub, GgufProbe(transport))
    private val quants = listOf("Q4_K_M", "Q4_0")

    private fun gguf(arch: String, payload: Int = 2_000) =
        GgufBytes().string("general.architecture", arch).u32("$arch.context_length", 8192).bytes(payload)

    /** A repository with [files] (name to bytes) at [commit], found by the search with [downloads]. */
    private fun publish(repo: String, commit: String, downloads: Long, vararg files: Pair<String, ByteArray>, gated: Boolean = false) {
        hub.repo(repo, commit, *files.map { (name, bytes) -> RepoFile(name, bytes.size.toLong()) }.toTypedArray())
        files.forEach { (name, bytes) -> transport.bodies[ArtifactResolver.resolveUrl(repo, commit, name)] = bytes }
        found += RepoSummary(repo, downloads = downloads, gated = gated)
    }

    @Test
    fun loadable_candidates_first_with_why_the_others_were_dropped() {
        publish("acme/small-GGUF", COMMIT_A, 500, "small-Q8_0.gguf" to gguf("qwen3"), "small-Q4_K_M.gguf" to gguf("qwen3"), "mmproj-small-F16.gguf" to gguf("clip", 10))
        publish("acme/future-GGUF", COMMIT_B, 9000, "future-Q4_K_M.gguf" to gguf("gemma9"))
        publish("acme/huge-GGUF", COMMIT_A, 7000, "huge-Q4_K_M.gguf" to gguf("llama", payload = 200_000))
        publish("acme/split-GGUF", COMMIT_B, 6000, "split-Q4_K_M-00001-of-00002.gguf" to gguf("llama"))
        publish("acme/gated-GGUF", COMMIT_A, 8000, "gated-Q4_K_M.gguf" to gguf("llama"), gated = true)
        publish("acme/known-GGUF", COMMIT_A, 10_000, "known-Q4_K_M.gguf" to gguf("llama"))

        val report = discovery.discover(ModelSearchQuery(pipelineTag = "text-generation"), quants, maxModelBytes = 100_000, skip = setOf("acme/known-GGUF"))

        val candidate = report.candidates.single()
        assertEquals("acme/small-GGUF", candidate.repo.id)
        assertEquals(COMMIT_A, candidate.commit)
        assertEquals("small-Q4_K_M.gguf", candidate.file.name, "quant priority, never the projector")
        assertEquals("qwen3", candidate.architecture)
        assertEquals(8192, candidate.contextLength)
        assertIs<ModelDiscovery.Outcome.Candidate>(report.outcomes.first())

        val dropped = report.outcomes.filterIsInstance<ModelDiscovery.Outcome.Dropped>().associate { it.repo.id to it.reason }
        assertTrue(dropped.getValue("acme/future-GGUF").contains("gemma9"))
        assertTrue(dropped.getValue("acme/huge-GGUF").contains("more than this device can hold"))
        assertTrue(dropped.getValue("acme/split-GGUF").contains("no single-file GGUF"))
        assertTrue(dropped.getValue("acme/gated-GGUF").contains("gated"))
        assertEquals("already in the app", dropped.getValue("acme/known-GGUF"))
        assertEquals(listOf("acme/known-GGUF", "acme/future-GGUF", "acme/gated-GGUF", "acme/huge-GGUF", "acme/split-GGUF"), dropped.keys.toList(), "most downloaded first")
    }

    @Test
    fun only_headers_are_read_and_nothing_too_big_or_known_is_touched() {
        publish("acme/small-GGUF", COMMIT_A, 500, "small-Q4_K_M.gguf" to gguf("qwen3", payload = 3_000_000))
        publish("acme/huge-GGUF", COMMIT_A, 7000, "huge-Q4_K_M.gguf" to gguf("llama", payload = 5_000_000))
        discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 4_000_000)
        assertEquals(listOf(ArtifactResolver.resolveUrl("acme/small-GGUF", COMMIT_A, "small-Q4_K_M.gguf") to 0L), transport.opens)
    }

    @Test
    fun a_repository_that_fails_is_dropped_and_the_rest_go_on() {
        publish("acme/broken-GGUF", COMMIT_A, 900, "broken-Q4_K_M.gguf" to gguf("llama"))
        hub.failures["acme/broken-GGUF"] = SourceException(SourceException.Kind.ACCESS_DENIED, "403")
        publish("acme/ok-GGUF", COMMIT_B, 100, "ok-Q4_K_M.gguf" to gguf("llama"))
        val report = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000)
        assertEquals(listOf("acme/ok-GGUF"), report.candidates.map { it.repo.id })
    }

    @Test
    fun cancelling_stops_between_repositories() {
        publish("acme/a-GGUF", COMMIT_A, 2, "a-Q4_K_M.gguf" to gguf("llama"))
        publish("acme/b-GGUF", COMMIT_B, 1, "b-Q4_K_M.gguf" to gguf("llama"))
        var checks = 0
        val report = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000) { ++checks > 1 }
        assertEquals(1, report.outcomes.size)
    }
}
