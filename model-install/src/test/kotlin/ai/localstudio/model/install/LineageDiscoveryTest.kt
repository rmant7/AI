package ai.localstudio.model.install

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineageDiscoveryTest {

    private val qwen = Lineages.byId("qwen")!!

    private fun repo(id: String, downloads: Long = 0, createdAt: String? = null, pipeline: String? = "text-generation", vararg tags: String) =
        RepoSummary(id, downloads = downloads, createdAt = createdAt, pipelineTag = pipeline, tags = tags.toList())

    @Test
    fun parameters_are_read_from_the_name_the_largest_count_winning() {
        assertEquals(30e9, LineageDiscovery.parametersFromName("unsloth/Qwen3-30B-A3B-GGUF"))
        assertEquals(70e9, LineageDiscovery.parametersFromName("acme/Student-1.5B-of-70B-GGUF"), "the largest, wherever it is in the name")
        assertEquals(1e9, LineageDiscovery.parametersFromName("bartowski/Llama-3.2-1B-Instruct-GGUF"), "not the 3.2 of the version")
        assertEquals(2.6e9, LineageDiscovery.parametersFromName("LiquidAI/LFM2.5-2.6B-GGUF"))
        assertEquals(0.5e9, LineageDiscovery.parametersFromName("Qwen/Qwen2.5-0.5B-Instruct-GGUF"))
        assertNull(LineageDiscovery.parametersFromName("microsoft/phi-4-gguf"))
        assertNull(LineageDiscovery.parametersFromName("HuggingFaceTB/SmolLM2-135M-Instruct-GGUF"))
    }

    @Test
    fun the_family_own_upload_beats_a_known_publisher_beats_anyone_else_for_the_same_base_model() {
        val (chosen, dropped) = LineageDiscovery.shortlist(
            qwen,
            listOf(
                repo("someone/Qwen3-4B-GGUF", downloads = 900_000, tags = arrayOf("base_model:quantized:Qwen/Qwen3-4B")),
                repo("unsloth/Qwen3-4B-GGUF", downloads = 500_000, tags = arrayOf("base_model:Qwen/Qwen3-4B")),
                repo("Qwen/Qwen3-4B-GGUF", downloads = 100_000, tags = arrayOf("base_model:Qwen/Qwen3-4B")),
            ),
            skip = emptySet(), maxModelBytes = 10_000_000_000, limit = 6,
        )
        assertEquals(listOf("Qwen/Qwen3-4B-GGUF"), chosen.map { it.id })
        assertEquals(setOf("someone/Qwen3-4B-GGUF", "unsloth/Qwen3-4B-GGUF"), dropped.map { it.repo.id }.toSet())
        assertTrue(dropped.all { it.reason == "same model as Qwen/Qwen3-4B-GGUF" })
    }

    @Test
    fun a_finetune_is_its_own_model_not_a_copy_of_what_it_was_built_from() {
        val (chosen, _) = LineageDiscovery.shortlist(
            qwen,
            listOf(
                repo("Qwen/Qwen3-4B-GGUF", tags = arrayOf("base_model:Qwen/Qwen3-4B")),
                repo("acme/Qwen3-4B-Medical-GGUF", tags = arrayOf("base_model:finetune:Qwen/Qwen3-4B")),
            ),
            skip = emptySet(), maxModelBytes = 10_000_000_000, limit = 6,
        )
        assertEquals(2, chosen.size)
    }

    @Test
    fun clearly_too_large_by_name_and_non_text_models_are_not_examined_and_say_why() {
        val (chosen, dropped) = LineageDiscovery.shortlist(
            qwen,
            listOf(
                repo("Qwen/Qwen3.8-27B-GGUF"),
                repo("Qwen/Qwen3-Embedding-0.6B-GGUF", pipeline = "feature-extraction"),
                repo("Qwen/Qwen3-14B-GGUF"),
                repo("Qwen/Qwen3-1.7B-GGUF"),
            ),
            skip = emptySet(), maxModelBytes = 9_584L * 1024 * 1024, limit = 6,
        )
        // 27B * 0.5 = 13.5 GB > 9.6 GB: dropped by name; 14B * 0.5 = 7 GB: examined, its real file decides.
        assertEquals(setOf("Qwen/Qwen3-14B-GGUF", "Qwen/Qwen3-1.7B-GGUF"), chosen.map { it.id }.toSet())
        val reasons = dropped.associate { it.repo.id to it.reason }
        assertTrue(reasons.getValue("Qwen/Qwen3.8-27B-GGUF").startsWith("~27B parameters by its name"))
        assertTrue(reasons.getValue("Qwen/Qwen3-Embedding-0.6B-GGUF").contains("feature-extraction"))
    }

    @Test
    fun the_family_own_and_the_newest_come_first_and_only_limit_are_examined() {
        val (chosen, _) = LineageDiscovery.shortlist(
            qwen,
            listOf(
                repo("bartowski/Qwen3-8B-GGUF", createdAt = "2026-09-01T00:00:00Z"),
                repo("Qwen/Qwen2.5-3B-GGUF", createdAt = "2025-01-01T00:00:00Z"),
                repo("Qwen/Qwen3.8-4B-GGUF", createdAt = "2026-09-30T00:00:00Z"),
                repo("x/Qwen3-1B-GGUF", createdAt = "2026-10-01T00:00:00Z"),
                repo("known/Qwen-GGUF"),
            ),
            skip = setOf("known/Qwen-GGUF"), maxModelBytes = 10_000_000_000, limit = 3,
        )
        assertEquals(listOf("Qwen/Qwen3.8-4B-GGUF", "Qwen/Qwen2.5-3B-GGUF", "bartowski/Qwen3-8B-GGUF"), chosen.map { it.id })
    }

    @Test
    fun each_family_is_searched_by_newest_and_most_downloaded_and_one_failed_search_does_not_lose_the_other() {
        val asked = mutableListOf<ModelSearchQuery>()
        val search = object : HuggingFaceSearch {
            override fun searchModels(query: ModelSearchQuery): List<RepoSummary> {
                asked += query
                if (query.sort == ModelSearchQuery.Sort.NEWEST) throw SourceException(SourceException.Kind.NETWORK, "timeout")
                return listOf(RepoSummary("Qwen/Qwen3-1.7B-GGUF", gated = true))
            }
        }
        val hub = FakeHuggingFace()
        val lineages = LineageDiscovery(search, ModelDiscovery(search, hub, GgufProbe(FakeTransport())))
        val result = lineages.discover(qwen, listOf("Q4_K_M"), 10_000_000_000, emptySet())

        assertEquals(setOf(ModelSearchQuery.Sort.NEWEST, ModelSearchQuery.Sort.DOWNLOADS), asked.map { it.sort }.toSet())
        assertTrue(asked.all { it.search == "Qwen" && "gguf" in it.tags && it.pipelineTag == null })
        assertNull(result.failure)
        assertEquals(1, result.found)
        assertTrue((result.outcomes.single() as ModelDiscovery.Outcome.Dropped).reason.contains("gated"))
    }

    @Test
    fun a_family_whose_every_search_failed_reports_the_failure() {
        val search = object : HuggingFaceSearch {
            override fun searchModels(query: ModelSearchQuery): List<RepoSummary> = throw SourceException(SourceException.Kind.NETWORK, "offline")
        }
        val result = LineageDiscovery(search, ModelDiscovery(search, FakeHuggingFace(), GgufProbe(FakeTransport())))
            .discover(qwen, listOf("Q4_K_M"), 10_000_000_000, emptySet())
        assertTrue(result.failure!!.contains("offline"))
        assertEquals(0, result.found)
    }

    @Test
    fun translation_families_come_before_the_general_ones_that_would_also_find_them() {
        val ids = Lineages.ALL.map { it.id }
        assertTrue(ids.indexOf("translategemma") < ids.indexOf("gemma"))
        assertTrue(Lineages.ALL.filter { it.searches.isEmpty() }.all { it.pipelineTag != null }, "catch-alls are scoped by task")
    }
}
