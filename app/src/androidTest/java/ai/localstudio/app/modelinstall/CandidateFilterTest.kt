package ai.localstudio.app.modelinstall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateFilterTest {

    private fun candidate(
        repo: String,
        gb: Double = 2.0,
        downloads: Long = 1_000,
        tags: List<String> = emptyList(),
        vision: Boolean = false,
        created: String? = null,
    ) = DiscoveredCandidate(
        repoId = repo, fileName = "m.gguf", filePath = "m-Q4_K_M.gguf", sizeBytes = (gb * 1e9).toLong(),
        architecture = "gemma3", contextLength = 4096, notes = emptyList(), commit = "a".repeat(40), downloads = downloads,
        tags = tags, createdAt = created,
        projector = if (vision) ai.localstudio.model.install.ProjectorFile(ai.localstudio.model.install.ModelFile(repo, "a".repeat(40), "mmproj-F16.gguf", 800_000_000), "gemma3") else null,
    )

    private val qwen = candidate("unsloth/Qwen3.5-4B-GGUF", gb = 2.7, downloads = 90_000, tags = listOf("gguf", "conversational"), created = "2026-08-01")
    private val gemma = candidate("unsloth/gemma-3-4b-it-GGUF", gb = 2.5, downloads = 400_000, tags = listOf("gguf", "image-text-to-text"), vision = true, created = "2026-03-01")
    private val hy = candidate("tencent/Hy-MT2-7B-GGUF", gb = 4.6, downloads = 411_914, tags = listOf("gguf", "translation"), created = "2026-05-20")

    private fun CandidateFilter.ok(label: String, c: DiscoveredCandidate, passes: Set<String> = emptySet(), tested: Boolean = false) =
        matches(label, c, passes, tested)

    @Test
    fun the_default_shows_everything() {
        assertTrue(CandidateFilter().isDefault)
        assertTrue(CandidateFilter().ok("chat:qwen", qwen) && CandidateFilter().ok("translation:hunyuan-mt", hy))
    }

    @Test
    fun vision_means_a_usable_projector_came_with_it_and_size_counts_it() {
        val vision = CandidateFilter(purpose = CandidateFilter.Purpose.VISION)
        assertTrue(vision.ok("chat:gemma", gemma))
        assertFalse(vision.ok("chat:qwen", qwen))
        assertFalse("2.5 GB + 0.8 GB projector is over 3 GB", CandidateFilter(maxBytes = 3_000_000_000).ok("chat:gemma", gemma))
        assertTrue(CandidateFilter(maxBytes = 3_000_000_000).ok("chat:qwen", qwen))
    }

    @Test
    fun translation_is_the_search_it_came_from_or_its_own_tag() {
        val translation = CandidateFilter(purpose = CandidateFilter.Purpose.TRANSLATION)
        assertTrue(translation.ok("translation:hunyuan-mt", hy))
        assertTrue(translation.ok("chat:qwen", candidate("x/y-GGUF", tags = listOf("translation"))))
        assertFalse(translation.ok("chat:qwen", qwen))
        assertFalse(CandidateFilter(purpose = CandidateFilter.Purpose.CHAT).ok("translation:hunyuan-mt", hy))
    }

    @Test
    fun every_word_must_match_the_name_file_architecture_or_a_tag() {
        assertTrue(CandidateFilter(text = "gemma image").ok("chat:gemma", gemma))
        assertFalse(CandidateFilter(text = "gemma translation").ok("chat:gemma", gemma))
        assertTrue(CandidateFilter(text = "TENCENT").ok("translation:hunyuan-mt", hy))
    }

    @Test
    fun downloads_and_what_this_phone_observed() {
        assertFalse(CandidateFilter(minDownloads = 100_000).ok("chat:qwen", qwen))
        assertTrue(CandidateFilter(minDownloads = 100_000).ok("chat:gemma", gemma))
        val works = CandidateFilter(status = CandidateFilter.Status.WORKS)
        val failed = CandidateFilter(status = CandidateFilter.Status.FAILED)
        val untested = CandidateFilter(status = CandidateFilter.Status.NOT_TESTED)
        assertTrue(works.ok("chat:qwen", qwen, passes = setOf("text"), tested = true))
        assertFalse(works.ok("chat:qwen", qwen, tested = true))
        assertTrue(failed.ok("chat:qwen", qwen, tested = true))
        assertFalse("a stale result is not a current failure", failed.ok("chat:qwen", qwen, tested = false))
        assertTrue(untested.ok("chat:qwen", qwen, tested = false))
    }

    @Test
    fun sorting_never_drops_anything() {
        val all = listOf(qwen, gemma, hy)
        assertEquals(all, CandidateFilter().sorted(all) { it })
        assertEquals(listOf(hy, gemma, qwen), CandidateFilter(sort = CandidateFilter.Sort.DOWNLOADS).sorted(all) { it })
        assertEquals(listOf(qwen, hy, gemma), CandidateFilter(sort = CandidateFilter.Sort.NEWEST).sorted(all) { it })
        assertEquals(listOf(qwen, gemma, hy), CandidateFilter(sort = CandidateFilter.Sort.SMALLEST).sorted(all) { it })
    }

    @Test
    fun what_the_tags_say_it_is_for_filters_and_is_searchable_by_name_and_synonym() {
        val rp = candidate("acme/storyteller-GGUF", tags = listOf("gguf", "creative-writing"))
        val coder = candidate("acme/x-GGUF", tags = listOf("gguf", "coder"))
        val roleplay = CandidateFilter(tagged = CandidatePurpose.ROLEPLAY)
        assertTrue(roleplay.ok("chat:mistral", rp))
        assertFalse(roleplay.ok("chat:qwen", qwen))
        assertFalse(roleplay.isDefault)
        assertTrue("purpose name", CandidateFilter(text = "roleplay").ok("chat:mistral", rp))
        assertTrue("part of a word", CandidateFilter(text = "role").ok("chat:mistral", rp))
        assertTrue("a synonym tag", CandidateFilter(text = "rp").ok("chat:mistral", rp))
        assertTrue(CandidateFilter(text = "code").ok("chat:x", coder))
        assertFalse(CandidateFilter(text = "role").ok("chat:qwen", qwen))
    }
}
