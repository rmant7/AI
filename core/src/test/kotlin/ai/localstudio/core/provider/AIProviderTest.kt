package ai.localstudio.core.provider

import ai.localstudio.core.registry.ModelDescriptor
import ai.localstudio.core.registry.RuntimeBinding
import ai.localstudio.core.runtime.LoadedModel
import ai.localstudio.core.runtime.ModelRuntime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A minimal [ModelRuntime] — nothing under test here ever actually calls [load]. */
private object NoopRuntime : ModelRuntime {
    override val kind get() = throw NotImplementedError()
    override fun canRun(model: ModelDescriptor, binding: RuntimeBinding) = true
    override suspend fun load(model: ModelDescriptor, binding: RuntimeBinding): LoadedModel = throw NotImplementedError()
}

/** What a provider with nothing to discover (local, AICore) looks like — see [AIProvider]'s own doc comment. */
private class NoDiscoveryProvider(override val id: String) : AIProvider {
    override val runtime: ModelRuntime = NoopRuntime
    override suspend fun discoverModels(): List<DiscoveredModel> = emptyList()
}

/** What an HTTP-backed provider looks like, without a real network call. */
private class FakeHttpProvider(override val id: String, private val body: String) : AIProvider {
    override val runtime: ModelRuntime = NoopRuntime
    override suspend fun discoverModels(): List<DiscoveredModel> = OpenAiModelsListParser.parse(body)
}

class AIProviderTest {

    @Test
    fun `a provider with nothing to discover returns an empty list, not an error`() = runBlocking {
        val provider = NoDiscoveryProvider("local")
        assertEquals(emptyList(), provider.discoverModels())
    }

    @Test
    fun `an HTTP-backed provider's discovery goes through the same parser as any other`() = runBlocking {
        val provider = FakeHttpProvider("groq", """{"data":[{"id":"openai/gpt-oss-120b","owned_by":"Groq"}]}""")

        val models = provider.discoverModels()

        assertEquals(1, models.size)
        assertEquals("openai/gpt-oss-120b", models.single().id)
        assertEquals("groq", provider.id)
    }

    @Test
    fun `every AIProvider exposes the same runtime regardless of how it discovers models`() {
        val local = NoDiscoveryProvider("local")
        val cloud = FakeHttpProvider("groq", "{}")
        assertTrue(local.runtime === NoopRuntime)
        assertTrue(cloud.runtime === NoopRuntime)
    }
}
