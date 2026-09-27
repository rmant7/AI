package ai.localstudio.core.catalog

import ai.localstudio.core.capability.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun entry(providerId: String, modelId: String, status: ModelStatus, vararg caps: Capability) =
    CatalogEntry(providerId, modelId, status, capabilities = caps.toSet())

class CatalogSelectorAutoTest {

    @Test
    fun `only a VERIFIED entry is eligible for Auto`() {
        val catalog = listOf(
            entry("groq", "experimental-model", ModelStatus.EXPERIMENTAL, Capability.TEXT_GENERATION),
            entry("groq", "verified-model", ModelStatus.VERIFIED, Capability.TEXT_GENERATION),
        )

        val picked = CatalogSelector.selectAuto(catalog, Capability.TEXT_GENERATION, setOf("groq"))

        assertEquals("verified-model", picked?.modelId)
    }

    @Test
    fun `DISCOVERED, DEPRECATED and UNAVAILABLE are never picked for Auto`() {
        val catalog = listOf(
            entry("groq", "d1", ModelStatus.DISCOVERED, Capability.TEXT_GENERATION),
            entry("groq", "d2", ModelStatus.DEPRECATED, Capability.TEXT_GENERATION),
            entry("groq", "d3", ModelStatus.UNAVAILABLE, Capability.TEXT_GENERATION),
        )

        assertNull(CatalogSelector.selectAuto(catalog, Capability.TEXT_GENERATION, setOf("groq")))
    }

    @Test
    fun `a provider not in enabledProviderIds is never picked, even if VERIFIED`() {
        val catalog = listOf(entry("groq", "m1", ModelStatus.VERIFIED, Capability.TEXT_GENERATION))

        assertNull(CatalogSelector.selectAuto(catalog, Capability.TEXT_GENERATION, enabledProviderIds = emptySet()))
    }

    @Test
    fun `a model without the requested capability is never picked`() {
        val catalog = listOf(entry("groq", "text-only", ModelStatus.VERIFIED, Capability.TEXT_GENERATION))

        assertNull(CatalogSelector.selectAuto(catalog, Capability.VISION, setOf("groq")))
    }

    @Test
    fun `providerPriority breaks a tie between two eligible providers`() {
        val catalog = listOf(
            entry("gemini", "gemini-model", ModelStatus.VERIFIED, Capability.VISION),
            entry("groq", "groq-model", ModelStatus.VERIFIED, Capability.VISION),
        )

        val picked = CatalogSelector.selectAuto(
            catalog,
            Capability.VISION,
            setOf("groq", "gemini"),
            providerPriority = listOf("groq", "gemini"),
        )

        assertEquals("groq", picked?.providerId)
    }

    @Test
    fun `a provider not named in providerPriority is still eligible, just lower priority`() {
        val catalog = listOf(entry("mistral", "m1", ModelStatus.VERIFIED, Capability.TEXT_GENERATION))

        val picked = CatalogSelector.selectAuto(
            catalog,
            Capability.TEXT_GENERATION,
            setOf("mistral"),
            providerPriority = listOf("groq", "gemini"), // neither is even in the catalog
        )

        assertEquals("mistral", picked?.providerId)
    }

    @Test
    fun `nothing eligible at all returns null`() {
        assertNull(CatalogSelector.selectAuto(emptyList(), Capability.TEXT_GENERATION, setOf("groq")))
    }
}

class CatalogSelectorManualTest {

    @Test
    fun `a manual pick works regardless of status`() {
        val catalog = listOf(entry("groq", "deprecated-model", ModelStatus.DEPRECATED, Capability.TEXT_GENERATION))

        val picked = CatalogSelector.selectManual(catalog, "groq", "deprecated-model", enabledProviderIds = setOf("groq"))

        assertEquals(ModelStatus.DEPRECATED, picked?.status)
    }

    @Test
    fun `a manual pick on a disabled provider returns null even if the model is in the catalog`() {
        val catalog = listOf(entry("groq", "m1", ModelStatus.VERIFIED, Capability.TEXT_GENERATION))

        assertNull(CatalogSelector.selectManual(catalog, "groq", "m1", enabledProviderIds = emptySet()))
    }

    @Test
    fun `a manual pick of a model not in the catalog at all returns null`() {
        assertNull(CatalogSelector.selectManual(emptyList(), "groq", "unknown-model", enabledProviderIds = setOf("groq")))
    }
}
