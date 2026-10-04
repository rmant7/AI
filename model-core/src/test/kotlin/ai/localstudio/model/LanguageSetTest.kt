package ai.localstudio.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageSetTest {

    @Test
    fun `legacy and differently-cased tags normalize to the same language`() {
        assertEquals("he", LanguageTags.normalize("iw"))
        assertEquals("pt-br", LanguageTags.normalize("pt_BR"))
        assertTrue(LanguageSet.of("he").contains("iw"))
        assertTrue(LanguageSet.of("iw").contains("HE"))
    }

    @Test
    fun `a bare language covers its regional forms and the other way round`() {
        assertTrue(LanguageSet.of("pt").contains("pt-BR"))
        assertTrue(LanguageSet.of("pt-BR").contains("pt"))
    }

    @Test
    fun `two different regional or script forms do not match each other`() {
        assertFalse(LanguageSet.of("zh-Hans").contains("zh-Hant"))
        assertFalse(LanguageSet.of("pt-BR").contains("pt-PT"))
        assertFalse(LanguageSet.of("en").contains("he"))
    }

    @Test
    fun `All contains everything`() {
        assertTrue(LanguageSet.All.contains("haw"))
    }

    @Test
    fun `serialized form is a star or a sorted array`() {
        val all = CatalogCodec.json.encodeToString(LanguageSet.serializer(), LanguageSet.All)
        val some = CatalogCodec.json.encodeToString(LanguageSet.serializer(), LanguageSet.of("ru", "en"))
        assertEquals("\"*\"", all)
        assertEquals("[\"en\",\"ru\"]", some)
        assertEquals(LanguageSet.All, CatalogCodec.json.decodeFromString(LanguageSet.serializer(), all))
        assertEquals(LanguageSet.of("en", "ru"), CatalogCodec.json.decodeFromString(LanguageSet.serializer(), some))
    }
}
