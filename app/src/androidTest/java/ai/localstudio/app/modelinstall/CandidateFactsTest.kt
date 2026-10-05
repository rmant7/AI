package ai.localstudio.app.modelinstall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateFactsTest {

    /** Shaped like a real Hub search item's tag list. */
    private val tags = listOf(
        "transformers", "gguf", "qwen2", "text-generation", "conversational", "code", "en", "zh",
        "base_model:Qwen/Qwen2.5-Coder-1.5B-Instruct",
        "base_model:quantized:Qwen/Qwen2.5-Coder-1.5B-Instruct",
        "license:apache-2.0", "endpoints_compatible", "region:us",
    )

    @Test
    fun purposes_come_only_from_tags_the_author_set() {
        assertEquals(listOf(CandidatePurpose.CHAT, CandidatePurpose.CODE), CandidateFacts.of(tags).purposes)
    }

    @Test
    fun base_model_tags_of_every_form_collapse_to_one_repo_id() {
        assertEquals(listOf("Qwen/Qwen2.5-Coder-1.5B-Instruct"), CandidateFacts.of(tags).baseModels)
    }

    @Test
    fun two_letter_tags_are_languages_but_not_two_letter_purposes() {
        val facts = CandidateFacts.of(tags + "rp")
        assertEquals(listOf("en", "zh"), facts.languages)
        assertTrue(CandidatePurpose.ROLEPLAY in facts.purposes)
    }

    @Test
    fun license_is_read_from_its_tag() {
        assertEquals("apache-2.0", CandidateFacts.of(tags).license)
    }

    @Test
    fun a_namespaced_tag_never_counts_as_a_purpose() {
        // "region:us", "dataset:code_x" -- ':' tags are metadata, not what the model is for.
        val facts = CandidateFacts.of(listOf("dataset:code", "region:us"))
        assertTrue(facts.purposes.isEmpty())
        assertTrue(facts.languages.isEmpty())
    }

    @Test
    fun no_tags_is_no_facts() {
        val facts = CandidateFacts.of(emptyList())
        assertTrue(facts.purposes.isEmpty())
        assertTrue(facts.baseModels.isEmpty())
        assertTrue(facts.languages.isEmpty())
        assertNull(facts.license)
    }
}
