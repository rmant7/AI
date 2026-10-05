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
        // Named, not the trailing-lambda shorthand: that now binds to
        // onOutcome (the last parameter), not isCancelled.
        val report = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000, isCancelled = { ++checks > 1 })
        assertEquals(1, report.outcomes.size)
    }

    @Test
    fun onOutcome_fires_as_each_repository_is_examined_not_after_the_batch() {
        publish("acme/a-GGUF", COMMIT_A, 2, "a-Q4_K_M.gguf" to gguf("llama"))
        publish("acme/b-GGUF", COMMIT_B, 1, "b-Q4_K_M.gguf" to gguf("llama"))
        val seenBeforeReturn = mutableListOf<String>()
        val report = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000, onOutcome = { seenBeforeReturn += it.repo.id })
        assertEquals(report.outcomes.map { it.repo.id }.toSet(), seenBeforeReturn.toSet())
        assertEquals(2, seenBeforeReturn.size)
    }

    private fun mmproj(type: String?, hasVision: Boolean = true, payload: Int = 1_000) =
        GgufBytes().string("general.architecture", "clip").bool("clip.has_vision_encoder", hasVision)
            .apply { if (type != null) string("clip.projector_type", type) }.bytes(payload)

    @Test
    fun a_vision_repository_is_one_candidate_whose_projector_is_part_of_it_at_the_same_commit() {
        publish(
            "acme/see-GGUF", COMMIT_A, 500,
            "see-Q4_K_M.gguf" to gguf("gemma3"),
            "mmproj-see-BF16.gguf" to mmproj("gemma3"),
            "mmproj-see-F16.gguf" to mmproj("gemma3"),
            "mmproj-see-Q8_0.gguf" to mmproj("gemma3"),
        )
        val report = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000)

        val candidate = report.candidates.single()
        assertEquals("see-Q4_K_M.gguf", candidate.file.name, "a projector is never the model")
        assertEquals("mmproj-see-F16.gguf", candidate.projector!!.file.name, "F16 first -- and not BF16, which merely contains f16")
        assertEquals("gemma3", candidate.projector!!.type)
        val artifact = candidate.artifact()
        assertEquals(artifact.main.revision, artifact.projector!!.file.revision)
        assertEquals(COMMIT_A, artifact.projector!!.file.revision)
        assertTrue(artifact.canCheck(VerifiedCapability.VISION))
        assertEquals(1, report.outcomes.size, "the projector is not an outcome of its own")
    }

    @Test
    fun a_projector_that_cannot_be_used_leaves_a_text_model_with_the_reason() {
        publish("acme/audio-GGUF", COMMIT_A, 3, "audio-Q4_K_M.gguf" to gguf("qwen3"), "mmproj-audio-F16.gguf" to mmproj("qwen2a", hasVision = false))
        publish("acme/future-GGUF", COMMIT_A, 2, "future-Q4_K_M.gguf" to gguf("qwen3"), "mmproj-future-F16.gguf" to mmproj("hologram9"))
        publish("acme/typeless-GGUF", COMMIT_A, 1, "typeless-Q4_K_M.gguf" to gguf("qwen3"), "mmproj-typeless-F16.gguf" to mmproj(null))
        val byId = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000).candidates.associateBy { it.repo.id }

        assertEquals(3, byId.size, "each is still a text candidate")
        assertTrue(byId.values.all { it.projector == null && !it.artifact().canCheck(VerifiedCapability.VISION) })
        assertTrue(byId.getValue("acme/audio-GGUF").notes.any { it.contains("no vision encoder") })
        assertTrue(byId.getValue("acme/future-GGUF").notes.any { it.contains("cannot load projector type \"hologram9\"") })
        assertTrue(byId.getValue("acme/typeless-GGUF").notes.any { it.contains("no projector type") })
    }

    @Test
    fun a_projector_that_would_not_fit_with_its_model_is_left_out_and_never_downloaded() {
        publish("acme/tight-GGUF", COMMIT_A, 1, "tight-Q4_K_M.gguf" to gguf("gemma3", payload = 60_000), "mmproj-tight-F16.gguf" to mmproj("gemma3", payload = 60_000))
        val candidate = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000).candidates.single()
        assertEquals(null, candidate.projector)
        assertTrue(candidate.notes.any { it.startsWith("projector mmproj-tight-F16.gguf left out: with it the model needs") })
        assertTrue(transport.opens.none { it.first.contains("mmproj") }, "not even its header is read")
    }

    @Test
    fun a_text_only_repository_is_examined_exactly_as_before() {
        publish("acme/plain-GGUF", COMMIT_A, 1, "plain-Q4_K_M.gguf" to gguf("llama"))
        val candidate = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000).candidates.single()
        assertEquals(null, candidate.projector)
        assertTrue(candidate.notes.none { it.startsWith("projector") })
        assertEquals(1, transport.opens.size)
    }

    @Test
    fun a_projector_in_the_same_quantization_and_smaller_is_still_never_picked_as_the_model() {
        // Listed first: FileSelection takes the first file in listing order that matches the quantization.
        publish("acme/same-GGUF", COMMIT_A, 1, "mmproj-same-Q4_0.gguf" to mmproj("gemma3", payload = 100), "same-Q4_0.gguf" to gguf("gemma3", payload = 20_000))
        val candidate = discovery.discover(ModelSearchQuery(), quants, maxModelBytes = 100_000).candidates.single()
        assertEquals("same-Q4_0.gguf", candidate.file.name)
        assertEquals("mmproj-same-Q4_0.gguf", candidate.projector!!.file.name)
    }
}

