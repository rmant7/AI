package ai.localstudio.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddingSpaceTest {

    private val e5 = Fixtures.sampleModel("multilingual-e5-small")
    private val iq4 = e5.variants.single { it.id.id.endsWith("iq4_xs") }
    private val q8 = e5.variants.single { it.id.id.endsWith("q8_0") }

    private fun withFacet(change: (EmbeddingFacet) -> EmbeddingFacet): ModelDefinition {
        val facet = e5.facet(Capabilities.TEXT_EMBEDDING) as EmbeddingFacet
        return e5.copy(capabilities = e5.capabilities + (Capabilities.TEXT_EMBEDDING to change(facet)))
    }

    @Test
    fun `the id is stable and well-formed`() {
        val id = EmbeddingSpace.idFor(e5, iq4)!!
        assertEquals(id, EmbeddingSpace.idFor(e5, iq4))
        assertTrue(Regex("^es1-[0-9a-f]{16}$").matches(id), id)
    }

    @Test
    fun `a different quantization of the same model is a different space`() {
        assertNotEquals(EmbeddingSpace.idFor(e5, iq4), EmbeddingSpace.idFor(e5, q8))
    }

    @Test
    fun `pooling, normalization, prefixes and dimensions each change the space`() {
        val base = EmbeddingSpace.idFor(e5, iq4)
        assertNotEquals(base, EmbeddingSpace.idFor(withFacet { it.copy(pooling = "cls") }, iq4))
        assertNotEquals(base, EmbeddingSpace.idFor(withFacet { it.copy(normalized = false) }, iq4))
        assertNotEquals(base, EmbeddingSpace.idFor(withFacet { it.copy(queryPrefix = null) }, iq4))
        assertNotEquals(base, EmbeddingSpace.idFor(withFacet { it.copy(dimensions = 768) }, iq4))
    }

    @Test
    fun `display-only changes do not force a reindex`() {
        assertEquals(EmbeddingSpace.idFor(e5, iq4), EmbeddingSpace.idFor(e5.copy(displayName = "Renamed"), iq4))
    }

    @Test
    fun `an upstream file change behind the same variant id is a different space`() {
        val rebuilt = iq4.copy(artifacts = iq4.artifacts.map { it.copy(sha256 = Fixtures.SHA_B) })
        assertNotEquals(EmbeddingSpace.idFor(e5, iq4), EmbeddingSpace.idFor(e5, rebuilt))
    }

    @Test
    fun `models without an embedding facet have no space`() {
        val whisper = Fixtures.sampleModel("whisper-base")
        assertNull(EmbeddingSpace.idFor(whisper, whisper.variants.single()))
    }
}
