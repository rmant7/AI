package ai.localstudio.sdk

import ai.localstudio.sdk.testing.FakeLocalAi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAiContractTest {

    private val textModel = LocalModel("qwen", "Qwen", setOf(LocalCapability.TEXT), mapOf(LocalCapability.TEXT to CheckResult.PASS), 2_000_000_000)
    private val visionModel = LocalModel(
        "gemma", "Gemma", setOf(LocalCapability.TEXT, LocalCapability.VISION),
        mapOf(LocalCapability.TEXT to CheckResult.PASS, LocalCapability.VISION to CheckResult.NOT_TESTED), 3_000_000_000,
    )
    private val png = LocalImage(byteArrayOf(1, 2, 3), "image/png")

    @Test
    fun `images make vision required and are kept as a collection in order`() {
        val second = LocalImage(byteArrayOf(4), "image/jpeg")
        val input = LocalAiInput("compare", listOf(png, second))
        assertEquals(setOf(LocalCapability.TEXT, LocalCapability.VISION), input.requiredCapabilities)
        assertEquals(listOf(png, second), input.images)
        assertEquals(setOf(LocalCapability.TEXT), LocalAiInput("hi").requiredCapabilities)
    }

    @Test
    fun `an image is copied in and out, never shared with the caller`() {
        val bytes = byteArrayOf(1, 2, 3)
        val image = LocalImage(bytes, "image/png")
        bytes[0] = 9
        image.bytes[1] = 9
        assertEquals(LocalImage(byteArrayOf(1, 2, 3), "image/png"), image)
    }

    @Test
    fun `offered is not proven`() {
        assertTrue(LocalCapability.VISION in visionModel.capabilities)
        assertFalse(visionModel.proven(LocalCapability.VISION))
        assertTrue(visionModel.proven(LocalCapability.TEXT))
    }

    @Test
    fun `images to a model that cannot see fail as unseen, not as an answer`() = runBlocking {
        val ai = FakeLocalAi(listOf(textModel, visionModel), chatModelId = "qwen")
        assertFailsWith<LocalAiException.ImageNotSeen> { ai.generate(LocalAiInput("what is this?", listOf(png))).toList() }
        assertEquals(listOf("echo: what is this?"), ai.generate(LocalAiInput("what is this?", listOf(png)), modelId = "gemma").toList())
        assertEquals(listOf("gemma"), ai.received.map { it.first })
    }

    @Test
    fun `an unknown model and a missing capability are told apart`() = runBlocking {
        val ai = FakeLocalAi(listOf(textModel))
        assertFailsWith<LocalAiException.UnknownModel> { ai.generate(LocalAiInput("hi"), modelId = "nope").toList() }
        val missing = assertFailsWith<LocalAiException.NoModel> {
            ai.translate(TranslationRequest("hi", Language("en", "English"), Language("fr", "French")))
        }
        assertEquals(LocalCapability.TRANSLATION, missing.capability)
    }

    @Test
    fun `verify is for an installed model, by its id`() = runBlocking {
        val checked = visionModel.copy(verified = mapOf(LocalCapability.VISION to CheckResult.PASS))
        val ai = FakeLocalAi(listOf(checked))
        assertEquals(mapOf(LocalCapability.VISION to CheckResult.PASS), ai.verify(checked.id))
        assertFailsWith<LocalAiException.UnknownModel> { ai.verify("nope") }
        Unit
    }

    @Test
    fun `options reject what no model can do`() {
        assertFailsWith<IllegalArgumentException> { GenerationOptions(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { GenerationOptions(temperature = -1.0) }
    }

    @Test
    fun `installed but unverified, stale and failed are all installed and none is proven`() {
        val states = mapOf(
            "unverified" to emptyMap(),
            "not tested" to mapOf(LocalCapability.VISION to CheckResult.NOT_TESTED),
            "stale" to mapOf(LocalCapability.VISION to CheckResult.STALE),
            "failed" to mapOf(LocalCapability.VISION to CheckResult.FAIL),
        )
        for ((what, verified) in states) {
            val model = visionModel.copy(verified = verified)
            assertTrue(LocalCapability.VISION in model.capabilities, what)
            assertFalse(model.proven(LocalCapability.VISION), what)
        }
        assertTrue(visionModel.copy(verified = mapOf(LocalCapability.VISION to CheckResult.PASS)).proven(LocalCapability.VISION))
    }

    @Test
    fun `an artifact ref names the main file and its projector`() {
        val main = ArtifactRef("acme/see-GGUF", "abc", "see-Q4_K_M.gguf")
        assertEquals("acme/see-GGUF|abc|see-Q4_K_M.gguf", main.key)
        assertEquals("acme/see-GGUF|abc|see-Q4_K_M.gguf|mmproj-F16.gguf", main.copy(projectorFile = "mmproj-F16.gguf").key)
    }

    @Test
    fun `a timeout is required and bounded by the deadline`() {
        assertEquals(10 * 60_000L, GenerationOptions().deadlineMs)
        assertFailsWith<IllegalArgumentException> { GenerationOptions(timeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { GenerationOptions(timeoutMs = 10_000, deadlineMs = 5_000) }
    }

    @Test
    fun `a query means the same everywhere -- capability, check result, source, words`() = runBlocking {
        val index = LocalModel(
            "custom-index", "Index-Translate-2B", setOf(LocalCapability.TEXT, LocalCapability.TRANSLATION, LocalCapability.VISION),
            mapOf(LocalCapability.TEXT to CheckResult.PASS, LocalCapability.TRANSLATION to CheckResult.PASS, LocalCapability.VISION to CheckResult.FAIL),
            2_000_000_000, source = ModelSource.DISCOVERED,
        )
        val gemma = visionModel.copy(verified = mapOf(LocalCapability.TEXT to CheckResult.PASS, LocalCapability.VISION to CheckResult.PASS), source = ModelSource.DISCOVERED)
        val stale = visionModel.copy(id = "old", verified = mapOf(LocalCapability.VISION to CheckResult.STALE))
        val ai = FakeLocalAi(listOf(textModel, index, gemma, stale))

        assertEquals(listOf("gemma"), ai.models(ModelQuery.proven(LocalCapability.VISION)).map { it.id })
        assertEquals(listOf("custom-index", "gemma", "old"), ai.models(ModelQuery(capability = LocalCapability.VISION)).map { it.id }, "offered, whatever the check says")
        assertEquals(listOf("old"), ai.models(ModelQuery(checkResults = setOf(CheckResult.STALE))).map { it.id })
        assertEquals(listOf("custom-index", "gemma"), ai.models(ModelQuery(sources = setOf(ModelSource.DISCOVERED))).map { it.id })
        assertEquals(listOf("custom-index"), ai.models(ModelQuery(text = "index translate")).map { it.id })
        assertEquals(listOf("qwen"), ai.models(ModelQuery(capability = LocalCapability.TEXT, checkResults = setOf(CheckResult.PASS), sources = setOf(ModelSource.CATALOG))).map { it.id })
    }

    @Test
    fun `untested counts as NOT_TESTED, and a candidate is always discovered`() {
        val candidate = ModelCandidate(
            id = "acme/a|c|a.gguf", artifact = ArtifactRef("acme/a", "c", "a.gguf"), repository = "acme/a", sizeBytes = 1,
            capabilities = setOf(LocalCapability.TEXT), installed = false,
        )
        assertTrue(ModelQuery(checkResults = setOf(CheckResult.NOT_TESTED)).matches(candidate))
        assertFalse(ModelQuery(sources = setOf(ModelSource.CATALOG)).matches(candidate))
        assertTrue(ModelQuery(sources = setOf(ModelSource.DISCOVERED), text = "acme").matches(candidate))
    }
}
