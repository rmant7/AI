package ai.localstudio.model

import ai.localstudio.model.Fixtures.artifact
import ai.localstudio.model.Fixtures.hf
import ai.localstudio.model.Fixtures.model
import ai.localstudio.model.Fixtures.validate
import ai.localstudio.model.Fixtures.variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DynamicSourceTest {

    private fun selection(
        repoIds: List<String> = listOf("owner/a-GGUF", "other/a-GGUF"),
        revision: String = "main",
        file: FileSelector = FileSelector.ByQuantization(listOf("Q4_K_M", "Q8_0"), ".gguf"),
    ) = ArtifactSource.HuggingFaceSelection(repoIds, revision, file)

    private fun dynamicModel(
        status: CatalogStatus = CatalogStatus.UNVERIFIED,
        source: ArtifactSource = selection(),
        sha256: String? = null,
        mirrors: List<ArtifactSource> = emptyList(),
    ) = model(
        status = status,
        variants = listOf(variant(artifacts = listOf(artifact(source = source, sha256 = sha256, mirrors = mirrors)))),
    )

    private fun violations(model: ModelDefinition): List<String> {
        val result = validate(model)
        return result.violations.map { it.message }
    }

    @Test
    fun `an unverified entry may use a dynamic selection`() {
        assertEquals(emptyList(), violations(dynamicModel()))
        assertEquals(emptyList(), violations(dynamicModel(source = selection(file = FileSelector.ExactName("mmproj-F16.gguf")))))
    }

    @Test
    fun `a dynamic selection is rejected at every status other than unverified`() {
        for (status in CatalogStatus.entries - CatalogStatus.UNVERIFIED) {
            val messages = violations(dynamicModel(status = status, sha256 = if (status == CatalogStatus.VERIFIED) Fixtures.SHA_A else null))
            assertTrue(messages.any { "only in unverified" in it }, "$status: $messages")
        }
    }

    @Test
    fun `a dynamic selection can never be a mirror, even of an unverified entry`() {
        val messages = violations(dynamicModel(source = hf(revision = "main"), mirrors = listOf(selection())))
        assertTrue(messages.any { "cannot be a mirror" in it }, messages.toString())
    }

    @Test
    fun `a dynamic selection cannot carry a sha256`() {
        val messages = violations(dynamicModel(sha256 = Fixtures.SHA_A))
        assertTrue(messages.any { "cannot carry a sha256" in it }, messages.toString())
    }

    @Test
    fun `malformed selections are rejected`() {
        val bad = listOf(
            selection(repoIds = emptyList()) to "at least one repository",
            selection(repoIds = listOf("no-owner")) to "malformed repository id",
            selection(repoIds = listOf("a/b", "a/b")) to "must be unique",
            selection(revision = " ") to "needs a revision",
            selection(file = FileSelector.ByQuantization(listOf("Q4_K_M"), "gguf")) to "extension",
            selection(file = FileSelector.ExactName("dir/mmproj.gguf")) to "bare file name",
        )
        for ((source, expected) in bad) {
            val messages = violations(dynamicModel(source = source))
            assertTrue(messages.any { expected in it }, "$source: expected \"$expected\", got $messages")
        }
    }

    private val alternatives = ArtifactSource.Alternatives(
        listOf(ArtifactSource.DirectUrl("https://huggingface.co/x/y/resolve/main/m.zip"), hf("m.zip", revision = "main")),
    )

    @Test
    fun `alternatives are dynamic - unverified only, never a mirror, never with a sha256`() {
        assertEquals(emptyList(), violations(dynamicModel(source = alternatives)))
        assertTrue(violations(dynamicModel(status = CatalogStatus.EXPERIMENTAL, source = alternatives)).any { "only in unverified" in it })
        assertTrue(violations(dynamicModel(source = hf(revision = "main"), mirrors = listOf(alternatives))).any { "cannot be a mirror" in it })
        assertTrue(violations(dynamicModel(source = alternatives, sha256 = Fixtures.SHA_A)).any { "cannot carry a sha256" in it })
    }

    @Test
    fun `every alternative is checked like a source of its own`() {
        val bad = listOf(
            ArtifactSource.Alternatives(listOf(hf())) to "at least two sources",
            ArtifactSource.Alternatives(listOf(hf(), hf())) to "must be unique",
            ArtifactSource.Alternatives(listOf(hf(), selection())) to "fixed sources",
            ArtifactSource.Alternatives(listOf(hf(), ArtifactSource.DirectUrl("http://huggingface.co/a"))) to "must be https",
            ArtifactSource.Alternatives(listOf(hf(), ArtifactSource.DirectUrl("https://evil.example/a"))) to "not in the allowlist",
        )
        for ((source, expected) in bad) {
            val messages = violations(dynamicModel(source = source))
            assertTrue(messages.any { expected in it }, "$source: expected \"$expected\", got $messages")
        }
    }

    @Test
    fun `only the dynamic sources report isDynamic`() {
        assertTrue(alternatives.isDynamic)
        assertTrue(selection().isDynamic)
        assertTrue(!hf().isDynamic)
        assertTrue(!ArtifactSource.DirectUrl("https://example.org/x").isDynamic)
    }

    @Test
    fun `repository order and revision are serialized explicitly and survive a round trip`() {
        val document = Fixtures.document(dynamicModel())
        val text = CatalogCodec.encode(document)
        assertTrue("\"type\":\"huggingface_selection\"" in text, text)
        assertTrue("\"repoIds\":[\"owner/a-GGUF\",\"other/a-GGUF\"]" in text, text)
        assertTrue("\"revision\":\"main\"" in text, text)
        val (decoded, problems) = CatalogCodec.decode(text)
        assertEquals(emptyList(), problems)
        assertEquals(document, decoded)
    }

    @Test
    fun `the embedding space of a dynamic source follows the selection rule`() {
        val e5 = Fixtures.sampleModel("multilingual-e5-small")
        fun spaceWith(source: ArtifactSource): String? {
            val variant = e5.variants.first().let { v -> v.copy(artifacts = v.artifacts.map { it.copy(sha256 = null, source = source) }) }
            return EmbeddingSpace.idFor(e5, variant)
        }
        val base = spaceWith(selection())
        assertEquals(base, spaceWith(selection()))
        assertNotEquals(base, spaceWith(selection(repoIds = listOf("other/a-GGUF", "owner/a-GGUF"))))
        assertNotEquals(base, spaceWith(selection(file = FileSelector.ByQuantization(listOf("Q8_0"), ".gguf"))))
    }
}
