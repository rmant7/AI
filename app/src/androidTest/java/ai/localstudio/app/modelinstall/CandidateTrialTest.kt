package ai.localstudio.app.modelinstall

import ai.localstudio.model.install.CandidateTier
import ai.localstudio.model.install.tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [CandidateTrial] decides a discovered candidate's tier from what one run
 * actually observed. Pure logic over a fake [TrialRuntime]; the real
 * load-and-generate through llama.cpp can only be checked on a device.
 */
class CandidateTrialTest {

    private val chat = FunctionalProbe.forLabel("chat")

    /** Every reading 100 ms after the last. */
    private fun steppingClock(): () -> Long {
        var now = 0L
        return { now += 100; now }
    }

    private fun trial() = CandidateTrial(steppingClock())

    private fun run(probes: List<FunctionalProbe> = chat, runtime: TrialRuntime) = runBlocking {
        trial().run("Pixel / API 37 / 16 GB / llama.cpp b10448", "llama_cpp", probes, runtime)
    }

    /** Loads, then answers each prompt with the chunks [replies] gives for it. */
    private fun answering(replies: (String) -> List<String>) = TrialRuntime { prompt, onLoaded, onChunk ->
        onLoaded()
        replies(prompt).forEach(onChunk)
    }

    private fun correct(prompt: String) = if ("France" in prompt) listOf("Pa", "ris", ".") else listOf("1", "2")

    @Test
    fun right_answers_to_every_probe_are_functional() {
        val result = run(runtime = answering(::correct))
        assertEquals(CandidateTier.FUNCTIONAL, result.tier())
        assertTrue(result.loaded)
        assertNull(result.error)
        assertEquals("Paris. | 12", result.sampleOutput)
    }

    @Test
    fun throughput_counts_only_the_time_between_chunks() {
        // 3 chunks + 2 chunks at 100 ms apart: 3 intervals over 300 ms.
        val result = run(runtime = answering(::correct))
        assertEquals(10.0, result.tokensPerSecond!!, 0.0001)
    }

    @Test
    fun a_throw_before_the_model_loaded_is_a_load_failure_and_stays_unverified() {
        val result = run(runtime = TrialRuntime { _, _, _ -> throw IllegalStateException("llama.cpp could not load model.gguf") })
        assertEquals(CandidateTier.UNVERIFIED, result.tier())
        assertEquals(false, result.loaded)
        assertTrue(result.error!!, result.error!!.startsWith("load failed: IllegalStateException: llama.cpp could not load"))
        assertNull(result.sampleOutput)
        assertNull(result.tokensPerSecond)
    }

    @Test
    fun a_throw_after_the_load_was_reported_is_a_generation_failure() {
        val result = run(runtime = TrialRuntime { _, onLoaded, _ -> onLoaded(); throw OutOfMemoryError("decode") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!, result.error!!.startsWith("generation failed: OutOfMemoryError"))
    }

    @Test
    fun text_itself_proves_the_load_even_without_the_callback() {
        val result = run(runtime = TrialRuntime { _, _, onChunk -> onChunk("Par"); throw RuntimeException("cut") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!.startsWith("generation failed"))
        assertEquals("Par", result.sampleOutput)
    }

    @Test
    fun a_fluent_wrong_answer_is_loadable_not_functional() {
        val result = run(runtime = answering { listOf("The capital is Lyon.") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!, result.error!!.startsWith("wrong answer to: What is the capital of France?"))
        assertEquals("The capital is Lyon.", result.sampleOutput)
    }

    @Test
    fun one_wrong_probe_out_of_two_is_not_functional() {
        val result = run(runtime = answering { prompt -> if ("France" in prompt) listOf("Paris") else listOf("13") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!, result.error!!.startsWith("wrong answer to: What is 7 + 5?"))
    }

    @Test
    fun an_empty_answer_is_loadable_not_functional() {
        val result = run(runtime = answering { listOf("  ", "\n") })
        assertEquals(CandidateTier.LOADABLE, result.tier())
        assertTrue(result.error!!.startsWith("empty answer to:"))
    }

    @Test
    fun the_expected_answer_is_matched_regardless_of_case() {
        assertTrue(FunctionalProbe("q", listOf("Paris")).passes("PARIS!"))
        assertTrue(FunctionalProbe.forLabel("translation").single().passes("Bonjour, mon ami."))
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
}
