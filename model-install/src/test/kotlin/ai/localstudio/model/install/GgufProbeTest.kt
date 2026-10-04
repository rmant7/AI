package ai.localstudio.model.install

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GgufProbeTest {

    private val url = "https://huggingface.co/acme/model-GGUF/resolve/$COMMIT_A/model-Q4_K_M.gguf"

    /** A real-shaped file: header, a large vocabulary, then megabytes of weights. */
    private fun model(arch: String) = GgufBytes()
        .string("general.architecture", arch)
        .string("general.name", "Acme 1B")
        .u32("$arch.context_length", 32768)
        .strings("tokenizer.ggml.tokens", List(20_000) { "token-$it" })
        .string("tokenizer.chat_template", "{% for m in messages %}{{ m.content }}{% endfor %}")
        .bytes(payload = 3_000_000)

    @Test
    fun a_loadable_model_is_judged_from_its_first_bytes() {
        val transport = FakeTransport().apply { bodies[url] = model("qwen3") }
        val result = assertIs<GgufProbe.Result.Probed>(GgufProbe(transport).probe(url))
        assertEquals(GgufCompatibility.Loadable("qwen3", 32768, emptyList()), result.compatibility)
        assertTrue(result.bytesRead < 64 * 1024, "read ${result.bytesRead} bytes of a 3+ MB file")
        assertEquals(listOf(url to 0L), transport.opens)
    }

    @Test
    fun an_architecture_the_bundled_llama_cpp_lacks_is_not_loadable() {
        val transport = FakeTransport().apply { bodies[url] = model("gemma9") }
        val result = assertIs<GgufProbe.Result.Probed>(GgufProbe(transport).probe(url))
        assertIs<GgufCompatibility.NotLoadable>(result.compatibility)
    }

    @Test
    fun the_chat_template_needs_the_whole_header_and_stays_within_the_budget() {
        val transport = FakeTransport().apply { bodies[url] = model("qwen3") }
        val full = assertIs<GgufProbe.Result.Probed>(GgufProbe(transport).probe(url) { false })
        assertTrue(full.metadata.complete)
        assertTrue(full.metadata.hasChatTemplate)
        assertEquals(20_000, full.metadata.vocabularySize)

        val tight = GgufProbe(transport, maxBytes = 50_000).probe(url) { false }
        assertTrue(assertIs<GgufProbe.Result.Unreadable>(tight).reason.contains("50000"))
    }

    @Test
    fun what_cannot_be_read_is_not_a_verdict() {
        val transport = FakeTransport().apply {
            bodies[url] = "<html>Access to model acme/model-GGUF is restricted</html>".toByteArray()
            errors["$url.down"] = ArrayDeque(listOf(IOException("connection refused")))
        }
        val probe = GgufProbe(transport)
        assertIs<GgufProbe.Result.Unreadable>(probe.probe(url))
        assertTrue(assertIs<GgufProbe.Result.Unreadable>(probe.probe("$url.down")).reason.contains("connection refused"))
        assertIs<GgufProbe.Result.Unreadable>(probe.probe("$url.missing"))
    }
}
