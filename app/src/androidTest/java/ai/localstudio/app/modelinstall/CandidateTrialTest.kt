package ai.localstudio.app.modelinstall

import ai.localstudio.app.localai.TranslationPrompts
import ai.localstudio.model.install.CandidateTier
import ai.localstudio.model.install.CheckStatus
import ai.localstudio.model.install.VerifiedCapability
import ai.localstudio.model.install.tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [CandidateTrial] decides, per capability, what one run on a device
 * actually observed. Pure logic over a fake [TrialRuntime]; the real
 * load-and-generate through llama.cpp can only be checked on a device.
 */
class CandidateTrialTest {

    private val textOnly = mapOf(VerifiedCapability.TEXT to FunctionalProbe.TEXT)

    /** Every reading 100 ms after the last. */
    private fun steppingClock(): () -> Long {
        var now = 0L
        return { now += 100; now }
    }

    private fun run(suites: Map<String, List<FunctionalProbe>> = textOnly, runtime: TrialRuntime) = runBlocking {
        CandidateTrial(steppingClock()).run("Pixel / API 37 / 16 GB / llama.cpp b10448", "llama_cpp", suites, runtime)
    }

    /** Loads, then answers each prompt with the chunks [replies] gives for it. */
    private fun answering(replies: (String) -> List<String>) = TrialRuntime { probe, onLoaded, onChunk ->
        onLoaded()
        replies(probe.prompt).forEach(onChunk)
    }

    private fun correctChat(prompt: String) = if ("France" in prompt) listOf("Pa", "ris", ".") else listOf("1", "2")

    private fun correctFrench(prompt: String) = listOf(
        when {
            "Good morning" in prompt -> "Bonjour, mon ami."
            "Thank you" in prompt -> "Merci beaucoup."
            else -> "Où est la gare ?"
        },
    )

    @Test
    fun right_answers_to_every_text_probe_pass_text() {
        val result = run(runtime = answering(::correctChat))
        assertEquals(CandidateTier.FUNCTIONAL, result.tier())
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TEXT))
        assertEquals(CheckStatus.NOT_TESTED, result.status(VerifiedCapability.TRANSLATION))
        assertNull(result.error)
        assertEquals("Paris. | 12", result.sampleOutput)
    }

    @Test
    fun throughput_counts_only_the_time_between_chunks() {
        // 3 chunks + 2 chunks at 100 ms apart: 3 intervals over 300 ms.
        val result = run(runtime = answering(::correctChat))
        assertEquals(10.0, result.tokensPerSecond!!, 0.0001)
    }

    @Test
    fun a_translation_model_that_translates_the_chat_questions_is_translation_only() {
        // What a dedicated translation model does with "What is the capital of France?": translates it.
        val result = run(
            FunctionalProbe.SUITES,
            answering { prompt -> if ("```" in prompt) correctFrench(prompt) else listOf("Quelle est la capitale de la France ?") },
        )
        assertEquals(CandidateTier.FUNCTIONAL, result.tier())
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.TEXT))
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TRANSLATION))
        assertEquals(listOf(VerifiedCapability.TRANSLATION), result.passed)
        assertTrue(result.error!!, result.error!!.startsWith("text: wrong answer to: What is the capital of France?"))
    }

    @Test
    fun translation_is_judged_on_letters_so_a_split_word_still_counts_and_is_kept_in_the_sample() {
        // Index-Translate-2B, build #471: "Bon jour, mon ami." -- a spelling slip, not a wrong translation.
        val result = run(
            mapOf(VerifiedCapability.TRANSLATION to FunctionalProbe.TRANSLATION),
            answering { prompt -> if ("Good morning" in prompt) listOf("Bon jour, mon ami.") else correctFrench(prompt) },
        )
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TRANSLATION))
        assertTrue(result.checks.getValue(VerifiedCapability.TRANSLATION).sample!!.startsWith("Bon jour, mon ami."))
    }

    @Test
    fun a_translation_into_the_wrong_language_fails() {
        // TranslateGemma-4B, build #471: Kinyarwanda for "Good morning, my friend."
        val result = run(
            mapOf(VerifiedCapability.TRANSLATION to FunctionalProbe.TRANSLATION),
            answering { listOf("Mwaramutse neza, nshuti yange.") },
        )
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.TRANSLATION))
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.checks.getValue(VerifiedCapability.TRANSLATION).detail!!.contains("expected bonjour"))
    }

    @Test
    fun the_translation_check_sends_exactly_what_the_translation_screen_sends() {
        val asked = mutableListOf<String>()
        run(mapOf(VerifiedCapability.TRANSLATION to FunctionalProbe.TRANSLATION), TrialRuntime { probe, onLoaded, onChunk ->
            asked += probe.prompt
            onLoaded()
            correctFrench(probe.prompt).forEach(onChunk)
        })
        assertEquals(TranslationPrompts.chatInstruction("English", "French", "Good morning, my friend."), asked.first())
        assertEquals(3, asked.size)
    }

    @Test
    fun one_capability_failing_mid_generation_does_not_skip_the_next() {
        var calls = 0
        val result = run(FunctionalProbe.SUITES, TrialRuntime { probe, onLoaded, onChunk ->
            calls++
            onLoaded()
            if (calls == 1) throw IllegalStateException("no complete answer within 10 min")
            correctFrench(probe.prompt).forEach(onChunk)
        })
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.TEXT))
        assertTrue(result.checks.getValue(VerifiedCapability.TEXT).detail!!.startsWith("generation failed: IllegalStateException"))
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TRANSLATION))
    }

    @Test
    fun a_throw_before_the_model_loaded_is_a_load_failure_with_nothing_checked() {
        val result = run(FunctionalProbe.SUITES, TrialRuntime { _, _, _ -> throw IllegalStateException("llama.cpp could not load model.gguf") })
        assertEquals(CandidateTier.UNVERIFIED, result.tier())
        assertEquals(false, result.loaded)
        assertTrue(result.error!!, result.error!!.startsWith("load failed: IllegalStateException: llama.cpp could not load"))
        assertTrue(result.checks.isEmpty())
        assertNull(result.sampleOutput)
        assertNull(result.tokensPerSecond)
    }

    @Test
    fun a_throw_after_the_load_was_reported_is_a_generation_failure() {
        val result = run(runtime = TrialRuntime { _, onLoaded, _ -> onLoaded(); throw OutOfMemoryError("decode") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.checks.getValue(VerifiedCapability.TEXT).detail!!.startsWith("generation failed: OutOfMemoryError"))
    }

    @Test
    fun text_itself_proves_the_load_even_without_the_callback() {
        val result = run(runtime = TrialRuntime { _, _, onChunk -> onChunk("Par"); throw RuntimeException("cut") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.loaded)
        assertEquals("Par", result.sampleOutput)
    }

    @Test
    fun a_fluent_wrong_answer_fails_its_capability() {
        val result = run(runtime = answering { listOf("The capital is Lyon.") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!, result.error!!.startsWith("text: wrong answer to: What is the capital of France?"))
        assertEquals("The capital is Lyon.", result.sampleOutput)
    }

    @Test
    fun one_wrong_probe_out_of_two_fails_the_capability() {
        val result = run(runtime = answering { prompt -> if ("France" in prompt) listOf("Paris") else listOf("13") })
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.TEXT))
        assertTrue(result.error!!, result.error!!.contains("wrong answer to: What is 7 + 5?"))
    }

    @Test
    fun an_empty_answer_fails() {
        val result = run(runtime = answering { listOf("  ", "\n") })
        assertTrue(result.checks.getValue(VerifiedCapability.TEXT).detail!!.startsWith("empty answer to:"))
    }

    @Test
    fun a_reply_cut_off_inside_its_reasoning_is_not_credited_even_if_the_draft_names_the_answer() {
        val result = run(runtime = answering { listOf("<think> Okay, the capital of France... I think it is Paris, but let me make sure") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!, result.error!!.startsWith("text: no answer to: What is the capital of France?"))
    }

    @Test
    fun only_the_answer_after_the_reasoning_is_judged() {
        val wrongAfterRightDraft = run(runtime = answering { listOf("<think>Paris? No.</think>", "Lyon") })
        assertEquals(CandidateTier.LOADABLE, wrongAfterRightDraft.tier())
        assertEquals("Lyon", wrongAfterRightDraft.sampleOutput)

        val reasonedThenRight = run(
            runtime = answering { prompt -> if ("France" in prompt) listOf("<think>hmm</think>\n\nParis") else listOf("<think>7+5</think>12") },
        )
        assertEquals(CandidateTier.FUNCTIONAL, reasonedThenRight.tier())
        assertEquals("Paris | 12", reasonedThenRight.sampleOutput)
    }

    @Test
    fun an_expected_answer_counts_only_as_a_whole_word_for_text() {
        val probe = FunctionalProbe("q", listOf("12"))
        assertTrue(probe.passes("12"))
        assertTrue(probe.passes("The answer is 12."))
        assertTrue(probe.passes("It is 12, of course"))
        assertFalse(probe.passes("120"))
        assertFalse(probe.passes("3.12"))
        assertFalse(probe.passes("12.5"))
        assertFalse(FunctionalProbe("q", listOf("Paris")).passes("Parisian"))
    }

    @Test
    fun letters_matching_ignores_case_accents_spacing_and_punctuation() {
        val probe = FunctionalProbe("q", listOf("bonjour"), FunctionalProbe.Match.LETTERS)
        assertTrue(probe.passes("BONJOUR !"))
        assertTrue(probe.passes("Bon jour"))
        assertTrue(FunctionalProbe("q", listOf("ou est la gare"), FunctionalProbe.Match.LETTERS).passes("Où est la gare ?"))
        assertFalse(probe.passes("Salut"))
    }

    @Test
    fun the_final_answer_is_what_follows_the_last_reasoning_block() {
        assertEquals("plain", finalAnswer("plain"))
        assertEquals(" b", finalAnswer("<think>x</think> a <think>y</think> b"))
        assertNull(finalAnswer("<think>never closed"))
    }

    @Test
    fun the_sample_is_truncated() {
        val result = run(runtime = answering { listOf("Paris " + "x".repeat(1_000)) })
        assertNotNull(result.sampleOutput)
        assertEquals(CandidateTrial.SAMPLE_CHARS, result.sampleOutput!!.length)
    }

    @Test
    fun cancellation_is_not_recorded_as_a_failure() {
        try {
            run(runtime = TrialRuntime { _, _, _ -> throw CancellationException("user left") })
            fail("cancellation must propagate, not become a verification")
        } catch (_: CancellationException) {
        }
    }

    private val main = ai.localstudio.model.install.ModelFile("acme/see-GGUF", "a".repeat(40), "see-Q4_K_M.gguf", 2_000_000_000)
    private val pair = ai.localstudio.model.install.ModelArtifact(
        main,
        ai.localstudio.model.install.ProjectorFile(main.copy(path = "mmproj-F16.gguf", sizeBytes = 800_000_000), "gemma3"),
    )

    /** What a model that sees: the digit, the colour, and text as before. */
    private fun seeing(probe: FunctionalProbe): List<String> = when (val image = probe.image) {
        is ProbeImage.Digit -> listOf(image.digit.toString())
        is ProbeImage.Disc -> listOf("Red.")
        null -> correctChat(probe.prompt)
    }

    @Test
    fun vision_is_checked_only_on_a_model_with_a_projector() {
        assertEquals(FunctionalProbe.SUITES, FunctionalProbe.suitesFor(ai.localstudio.model.install.ModelArtifact(main)))
        assertEquals(FunctionalProbe.VISION, FunctionalProbe.suitesFor(pair)[VerifiedCapability.VISION])

        val textOnlyModel = run(FunctionalProbe.suitesFor(ai.localstudio.model.install.ModelArtifact(main)), TrialRuntime { probe, onLoaded, onChunk ->
            onLoaded()
            (if (probe.prompt.contains("French")) correctFrench(probe.prompt) else correctChat(probe.prompt)).forEach(onChunk)
        })
        assertEquals(CheckStatus.NOT_TESTED, textOnlyModel.status(VerifiedCapability.VISION))
    }

    @Test
    fun seeing_both_images_and_answering_text_afterwards_passes_vision_in_order_text_image_image_text() {
        val asked = mutableListOf<ProbeImage?>()
        val result = run(
            mapOf(VerifiedCapability.TEXT to FunctionalProbe.TEXT, VerifiedCapability.VISION to FunctionalProbe.VISION),
            TrialRuntime { probe, onLoaded, onChunk ->
                asked += probe.image
                onLoaded()
                seeing(probe).forEach(onChunk)
            },
        )
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TEXT))
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.VISION))
        assertEquals(listOf(null, null, ProbeImage.Digit(7), ProbeImage.Disc(ProbeImage.Disc.RED), null), asked)
    }

    @Test
    fun a_wrong_text_answer_after_the_images_fails_vision() {
        var imagesSeen = 0
        val result = run(
            mapOf(VerifiedCapability.TEXT to FunctionalProbe.TEXT, VerifiedCapability.VISION to FunctionalProbe.VISION),
            TrialRuntime { probe, onLoaded, onChunk ->
                onLoaded()
                if (probe.image != null) imagesSeen++
                // Memory left over from the image turn: the next text answer is about the image.
                (if (probe.image == null && imagesSeen > 0) listOf("7") else seeing(probe)).forEach(onChunk)
            },
        )
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TEXT))
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.VISION))
        assertTrue(result.checks.getValue(VerifiedCapability.VISION).detail!!.contains("text after the images"))
    }

    @Test
    fun an_image_the_model_did_not_see_fails_vision_and_leaves_text_alone() {
        val result = run(
            mapOf(VerifiedCapability.TEXT to FunctionalProbe.TEXT, VerifiedCapability.VISION to FunctionalProbe.VISION),
            TrialRuntime { probe, onLoaded, onChunk ->
                onLoaded()
                if (probe.image != null) throw IllegalStateException("the image was not seen: no vision projector loaded for this model")
                seeing(probe).forEach(onChunk)
            },
        )
        assertEquals(CheckStatus.PASS, result.status(VerifiedCapability.TEXT))
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.VISION))
        assertTrue(result.checks.getValue(VerifiedCapability.VISION).detail!!.contains("the image was not seen"))
    }

    @Test
    fun a_wrong_digit_fails_vision() {
        val result = run(
            mapOf(VerifiedCapability.VISION to FunctionalProbe.VISION),
            TrialRuntime { probe, onLoaded, onChunk ->
                onLoaded()
                (if (probe.image is ProbeImage.Digit) listOf("1") else seeing(probe)).forEach(onChunk)
            },
        )
        assertEquals(CheckStatus.FAIL, result.status(VerifiedCapability.VISION))
        assertTrue(result.checks.getValue(VerifiedCapability.VISION).detail!!.contains("the digit 7"))
    }
}
