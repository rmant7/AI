package ai.localstudio.core.catalog

import ai.localstudio.core.provider.DiscoveredModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun discovered(id: String) = DiscoveredModel(id = id, raw = buildJsonObject { put("id", id) })

private val KNOWN = setOf("groq", "gemini")

class ModelCatalogTest {

    @Test
    fun `with only a repo catalog, entries pass through unchanged`() {
        val repo = listOf(CatalogEntry("groq", "openai/gpt-oss-120b", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(repo, knownProviderIds = KNOWN)

        assertEquals(repo, merged)
    }

    @Test
    fun `a remote entry for the same key replaces the repo entry wholesale, status included`() {
        val repo = listOf(CatalogEntry("groq", "m1", ModelStatus.EXPERIMENTAL, notes = "old note"))
        val remote = listOf(CatalogEntry("groq", "m1", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(repo, remote, knownProviderIds = KNOWN)

        val entry = merged.single { it.modelId == "m1" }
        assertEquals(ModelStatus.VERIFIED, entry.status)
        assertNull(entry.notes)
    }

    @Test
    fun `a remote entry for a provider not in knownProviderIds is dropped entirely`() {
        val remote = listOf(CatalogEntry("evil-provider", "m1", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(repoEntries = emptyList(), remoteEntries = remote, knownProviderIds = KNOWN)

        assertEquals(emptyList(), merged)
    }

    @Test
    fun `a repo entry for a provider not in knownProviderIds is dropped too`() {
        val repo = listOf(CatalogEntry("removed-provider", "m1", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(repo, knownProviderIds = KNOWN)

        assertEquals(emptyList(), merged)
    }

    @Test
    fun `a newly discovered model not in repo or remote becomes DISCOVERED, never higher`() {
        val merged = ModelCatalog.merge(
            repoEntries = emptyList(),
            discoveredByProvider = mapOf("groq" to listOf(discovered("brand-new-model"))),
            knownProviderIds = KNOWN,
        )

        assertEquals(ModelStatus.DISCOVERED, merged.single().status)
    }

    @Test
    fun `a discovered model matching a known VERIFIED entry keeps VERIFIED, not downgraded to DISCOVERED`() {
        val repo = listOf(CatalogEntry("groq", "m1", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(
            repo,
            discoveredByProvider = mapOf("groq" to listOf(discovered("m1"))),
            knownProviderIds = KNOWN,
        )

        assertEquals(ModelStatus.VERIFIED, merged.single().status)
    }

    @Test
    fun `a known VERIFIED model the provider no longer lists becomes UNAVAILABLE`() {
        val repo = listOf(CatalogEntry("groq", "gone-model", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(
            repo,
            discoveredByProvider = mapOf("groq" to emptyList()),
            knownProviderIds = KNOWN,
        )

        assertEquals(ModelStatus.UNAVAILABLE, merged.single().status)
    }

    @Test
    fun `a DEPRECATED model the provider no longer lists stays DEPRECATED, not UNAVAILABLE`() {
        val repo = listOf(CatalogEntry("groq", "old-model", ModelStatus.DEPRECATED))

        val merged = ModelCatalog.merge(
            repo,
            discoveredByProvider = mapOf("groq" to emptyList()),
            knownProviderIds = KNOWN,
        )

        assertEquals(ModelStatus.DEPRECATED, merged.single().status)
    }

    @Test
    fun `a DEPRECATED model the provider DOES still list also stays DEPRECATED`() {
        // Being reachable again must never override an editorial DEPRECATED
        // judgment — that's what actually distinguishes it from UNAVAILABLE.
        val repo = listOf(CatalogEntry("groq", "old-model", ModelStatus.DEPRECATED))

        val merged = ModelCatalog.merge(
            repo,
            discoveredByProvider = mapOf("groq" to listOf(discovered("old-model"))),
            knownProviderIds = KNOWN,
        )

        assertEquals(ModelStatus.DEPRECATED, merged.single().status)
    }

    @Test
    fun `omitting a provider from discoveredByProvider leaves its known entries untouched`() {
        // A failed discovery call (network error) must not read as "this
        // provider has zero models now" — the caller signals that by simply
        // not including the provider's key at all, not by an empty list.
        val repo = listOf(CatalogEntry("groq", "m1", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(repo, discoveredByProvider = emptyMap(), knownProviderIds = KNOWN)

        assertEquals(ModelStatus.VERIFIED, merged.single().status)
    }

    @Test
    fun `discovery for a provider not in knownProviderIds is ignored entirely`() {
        val merged = ModelCatalog.merge(
            repoEntries = emptyList(),
            discoveredByProvider = mapOf("unknown-provider" to listOf(discovered("m1"))),
            knownProviderIds = KNOWN,
        )

        assertEquals(emptyList(), merged)
    }

    @Test
    fun `two providers' discovery results are handled independently`() {
        val repo = listOf(
            CatalogEntry("groq", "groq-model", ModelStatus.VERIFIED),
            CatalogEntry("gemini", "gemini-model", ModelStatus.VERIFIED),
        )

        val merged = ModelCatalog.merge(
            repo,
            discoveredByProvider = mapOf(
                "groq" to listOf(discovered("groq-model")),
                "gemini" to emptyList(), // gemini's model vanished
            ),
            knownProviderIds = KNOWN,
        )

        assertEquals(ModelStatus.VERIFIED, merged.single { it.providerId == "groq" }.status)
        assertEquals(ModelStatus.UNAVAILABLE, merged.single { it.providerId == "gemini" }.status)
    }

    @Test
    fun `remote, then discovery, compose correctly on top of the repo catalog`() {
        val repo = listOf(CatalogEntry("groq", "m1", ModelStatus.EXPERIMENTAL))
        val remote = listOf(CatalogEntry("groq", "m1", ModelStatus.VERIFIED))

        val merged = ModelCatalog.merge(
            repo,
            remote,
            discoveredByProvider = mapOf("groq" to listOf(discovered("m1"), discovered("m2"))),
            knownProviderIds = KNOWN,
        )

        assertEquals(2, merged.size)
        assertEquals(ModelStatus.VERIFIED, merged.single { it.modelId == "m1" }.status)
        assertEquals(ModelStatus.DISCOVERED, merged.single { it.modelId == "m2" }.status)
    }
}
