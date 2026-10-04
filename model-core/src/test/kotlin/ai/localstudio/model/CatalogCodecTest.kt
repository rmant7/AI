package ai.localstudio.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogCodecTest {

    @Test
    fun `sample catalog decodes and survives an encode-decode round trip unchanged`() {
        val (first, violations) = CatalogCodec.decode(Fixtures.sampleText())
        assertEquals(emptyList(), violations)
        val reEncoded = CatalogCodec.encode(first!!)
        val (second, again) = CatalogCodec.decode(reEncoded)
        assertEquals(emptyList(), again)
        assertEquals(first, second)
    }

    @Test
    fun `unknown capability is kept verbatim, including fields this build has no type for`() {
        val model = Fixtures.sampleModel("future-video-gen")
        val facet = model.capabilities.getValue(CapabilityId("video.generation"))
        assertIs<JsonCapabilityFacet>(facet)
        assertNull(facet.decodeError)
        assertEquals(JsonPrimitive(8), facet.raw["maxSeconds"])
        assertEquals(JsonArray(listOf(JsonPrimitive("480p"), JsonPrimitive("720p"))), facet.raw["resolutions"])

        val roundTripped = CatalogCodec.decode(CatalogCodec.encode(Fixtures.document(model))).first!!.models.single()
        assertEquals(facet, roundTripped.capabilities.getValue(CapabilityId("video.generation")))
    }

    @Test
    fun `unknown capability still exposes requiresRoles for role gating`() {
        val facet = Fixtures.sampleModel("future-video-gen").capabilities.getValue(CapabilityId("video.generation"))
        assertEquals(setOf(ArtifactRole("vae")), facet.requiresRoles)
    }

    @Test
    fun `built-in capability without a dedicated facet type decodes as GenericFacet`() {
        val facet = Fixtures.sampleModel("gemma-4-e4b-it").capabilities.getValue(Capabilities.VISION)
        assertEquals(GenericFacet(setOf(ArtifactRoles.PROJECTOR)), facet)
    }

    @Test
    fun `typed facets decode into their own types`() {
        assertIs<TranslationFacet>(Fixtures.sampleModel("translategemma-4b-it").facet(Capabilities.TRANSLATION))
        assertIs<SpeechToTextFacet>(Fixtures.sampleModel("whisper-base").facet(Capabilities.SPEECH_TO_TEXT))
        assertIs<TtsFacet>(Fixtures.sampleModel("piper-en-us-amy-low").facet(Capabilities.TEXT_TO_SPEECH))
        assertIs<EmbeddingFacet>(Fixtures.sampleModel("multilingual-e5-small").facet(Capabilities.TEXT_EMBEDDING))
    }

    @Test
    fun `a newer schema yields no models and a document-level violation`() {
        val (document, violations) = CatalogCodec.decode("""{"schema": 2, "catalogId": "x", "catalogVersion": "1", "models": []}""")
        assertNull(document)
        assertNull(violations.single().modelId)
    }

    @Test
    fun `missing header fields reject the whole document`() {
        val (document, violations) = CatalogCodec.decode("""{"schema": 1, "models": []}""")
        assertNull(document)
        assertNull(violations.single().modelId)
        assertNull(CatalogCodec.decode("not json").first)
    }

    @Test
    fun `one undecodable model is dropped, the others are kept`() {
        val good = CatalogCodec.json.encodeToString(ModelDefinition.serializer(), Fixtures.model("good"))
        val text = """
            {"schema": 1, "catalogId": "x", "catalogVersion": "1", "allowedHosts": ["huggingface.co"],
             "models": [ {"id": "broken", "status": "no-such-status"}, $good ]}
        """.trimIndent()
        val loaded = CatalogLoader.load(text, CatalogTrust.Bundled)
        assertEquals(listOf("good"), loaded.models.map { it.id.id })
        assertEquals("broken", loaded.violations.single().modelId)
        assertTrue(loaded.isUsable)
    }

    @Test
    fun `a malformed facet of a known capability is kept as raw JSON with its decode error`() {
        val text = """
            {"schema": 1, "catalogId": "x", "catalogVersion": "1", "allowedHosts": ["huggingface.co"],
             "models": [ {"id": "m", "family": "f", "displayName": "M", "status": "unverified",
               "capabilities": {"text.translation": {"targetLanguages": 42}},
               "variants": [] } ]}
        """.trimIndent()
        val facet = CatalogCodec.decode(text).first!!.models.single().capabilities.getValue(Capabilities.TRANSLATION)
        assertIs<JsonCapabilityFacet>(facet)
        assertTrue(facet.decodeError != null)
    }

    @Test
    fun `defaults are not written, so the encoded catalog stays minimal`() {
        val encoded = CatalogCodec.encode(Fixtures.document(Fixtures.model()))
        val model = (CatalogCodec.json.parseToJsonElement(encoded) as JsonObject)["models"] as JsonArray
        val variant = ((model.single() as JsonObject)["variants"] as JsonArray).single() as JsonObject
        assertNull(variant["metrics"])
        assertNull(variant["resources"])
    }
}
