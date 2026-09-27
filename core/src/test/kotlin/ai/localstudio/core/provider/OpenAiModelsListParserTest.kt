package ai.localstudio.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiModelsListParserTest {

    @Test
    fun `the standard OpenAI-compatible shape parses id, owned_by and created`() {
        val body = """
            {"object":"list","data":[
                {"id":"openai/gpt-oss-120b","object":"model","created":1700000000,"owned_by":"Groq"},
                {"id":"llama-3.3-70b-versatile","object":"model","created":1700000001,"owned_by":"Meta"}
            ]}
        """.trimIndent()

        val models = OpenAiModelsListParser.parse(body)

        assertEquals(2, models.size)
        assertEquals("openai/gpt-oss-120b", models[0].id)
        assertEquals("Groq", models[0].ownedBy)
        assertEquals(1700000000L, models[0].createdEpochSeconds)
    }

    @Test
    fun `an entry missing created or owned_by still parses, with those fields null`() {
        // Real device report shape: not every provider fills in every field
        // the "standard" shape describes.
        val body = """{"data":[{"id":"bare-model"}]}"""

        val models = OpenAiModelsListParser.parse(body)

        assertEquals(1, models.size)
        assertEquals("bare-model", models[0].id)
        assertNull(models[0].ownedBy)
        assertNull(models[0].createdEpochSeconds)
    }

    @Test
    fun `an entry with no id is dropped rather than failing the whole response`() {
        val body = """{"data":[{"owned_by":"nobody"},{"id":"real-model"}]}"""

        val models = OpenAiModelsListParser.parse(body)

        assertEquals(1, models.size)
        assertEquals("real-model", models[0].id)
    }

    @Test
    fun `provider-specific extra fields (OpenRouter's context_length, pricing) survive in raw`() {
        val body = """
            {"data":[{
                "id":"meta-llama/llama-4-maverick-17b-128e-instruct",
                "context_length": 131072,
                "pricing": {"prompt": "0", "completion": "0"}
            }]}
        """.trimIndent()

        val models = OpenAiModelsListParser.parse(body)

        assertEquals(1, models.size)
        assertEquals(131072L, models[0].raw["context_length"]?.let { it.toString().toLongOrNull() })
        assertTrue(models[0].raw.containsKey("pricing"))
    }

    @Test
    fun `an empty data array parses to an empty list`() {
        assertEquals(emptyList(), OpenAiModelsListParser.parse("""{"object":"list","data":[]}"""))
    }

    @Test
    fun `a response with no data field at all parses to an empty list, not a crash`() {
        assertEquals(emptyList(), OpenAiModelsListParser.parse("""{"object":"list"}"""))
    }

    @Test
    fun `malformed JSON parses to an empty list, not a thrown exception`() {
        assertEquals(emptyList(), OpenAiModelsListParser.parse("not json at all"))
        assertEquals(emptyList(), OpenAiModelsListParser.parse(""))
    }

    @Test
    fun `a data array containing a non-object entry is skipped, not fatal to the whole response`() {
        val body = """{"data":["not-an-object",{"id":"good-model"}]}"""

        val models = OpenAiModelsListParser.parse(body)

        assertEquals(1, models.size)
        assertEquals("good-model", models[0].id)
    }
}
