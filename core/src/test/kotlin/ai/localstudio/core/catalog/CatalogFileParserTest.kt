package ai.localstudio.core.catalog

import ai.localstudio.core.capability.Capability
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogFileParserTest {

    @Test
    fun `a minimal entry parses with its defaults`() {
        val body = """{"entries":[{"providerId":"groq","modelId":"openai/gpt-oss-120b","status":"VERIFIED"}]}"""

        val entries = CatalogFileParser.parse(body)

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("groq", entry.providerId)
        assertEquals("openai/gpt-oss-120b", entry.modelId)
        assertEquals(ModelStatus.VERIFIED, entry.status)
        assertEquals(emptySet(), entry.capabilities)
        assertEquals(null, entry.contextWindow)
    }

    @Test
    fun `a full entry parses every field`() {
        val body = """{"entries":[{
            "providerId":"gemini","modelId":"gemini-3.7-flash","status":"EXPERIMENTAL",
            "capabilities":["vision","text_generation"],"contextWindow":1048576,
            "displayName":"Gemini 3.7 Flash","notes":"preview release"
        }]}"""

        val entry = CatalogFileParser.parse(body).single()

        assertEquals(setOf(Capability.VISION, Capability.TEXT_GENERATION), entry.capabilities)
        assertEquals(1048576, entry.contextWindow)
        assertEquals("Gemini 3.7 Flash", entry.displayName)
        assertEquals("preview release", entry.notes)
    }

    @Test
    fun `an empty entries list parses to an empty list`() {
        assertEquals(emptyList(), CatalogFileParser.parse("""{"entries":[]}"""))
    }

    @Test
    fun `a malformed status value throws rather than silently dropping the entry`() {
        assertFailsWith<SerializationException> {
            CatalogFileParser.parse("""{"entries":[{"providerId":"x","modelId":"y","status":"NOT_A_REAL_STATUS"}]}""")
        }
    }

    @Test
    fun `parseOrEmpty swallows a malformed file instead of throwing`() {
        assertEquals(emptyList(), CatalogFileParser.parseOrEmpty("not json"))
        assertEquals(emptyList(), CatalogFileParser.parseOrEmpty("""{"entries":[{"status":"NOT_REAL"}]}"""))
    }

    @Test
    fun `parseOrEmpty still parses a well-formed file normally`() {
        val entries = CatalogFileParser.parseOrEmpty("""{"entries":[{"providerId":"a","modelId":"b","status":"DISCOVERED"}]}""")
        assertTrue(entries.isNotEmpty())
    }
}
