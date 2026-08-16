package ai.localstudio.core

import ai.localstudio.core.capability.Capability
import ai.localstudio.core.pipeline.PipelineCodec
import ai.localstudio.core.pipeline.PipelineValidator
import ai.localstudio.core.registry.InstallState
import ai.localstudio.core.registry.ModelRegistry
import ai.localstudio.core.registry.RegistryEntry
import ai.localstudio.core.registry.SuitabilityScorer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON shipped in the repository is part of the contract: if `pipelines/`
 * or `registry/` drifts away from the code, this fails.
 */
class RepositoryAssetsTest {

    private fun repoDir(name: String): File =
        listOf(File("../$name"), File(name)).firstOrNull { it.isDirectory }
            ?: error("Directory $name not found relative to ${File(".").absolutePath}")

    @Test
    fun `every shipped pipeline parses and validates`() {
        val files = repoDir("pipelines").listFiles { f: File -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no pipelines found")

        for (file in files) {
            val spec = PipelineCodec.decodePipeline(file.readText())
            assertEquals(
                emptyList(),
                PipelineValidator.validate(spec),
                "pipeline ${file.name} is invalid",
            )
            assertTrue(spec.requiredCapabilities().isNotEmpty(), "pipeline ${file.name} requires no capability")
        }
    }

    @Test
    fun `a pipeline survives a round trip through json`() {
        val file = repoDir("pipelines").resolve("voice_rag.json")
        val spec = PipelineCodec.decodePipeline(file.readText())

        assertEquals(spec, PipelineCodec.decodePipeline(PipelineCodec.encodePipeline(spec)))
    }

    @Test
    fun `the example catalog loads into the registry and can be ranked`() {
        val catalog = PipelineCodec.decodeCatalog(repoDir("registry").resolve("catalog.example.json").readText())
        val registry = ModelRegistry()

        val fresh = registry.merge(catalog)

        assertEquals(catalog.models.size, fresh.size)
        assertTrue(registry.providing(Capability.SPEECH_TO_TEXT).isNotEmpty())
        assertTrue(registry.providing(Capability.EMBEDDING).isNotEmpty())

        val ranked = SuitabilityScorer().rank(
            models = registry.all().map { it.model },
            device = device(availableRamBytes = 10 * GB),
            capability = Capability.TEXT_GENERATION,
        )
        assertTrue(ranked.isNotEmpty(), "no model from the example catalog runs on the reference device")
        assertTrue(ranked.all { it.suitability.binding.effectiveRequiredRamBytes <= 6 * GB })
    }

    @Test
    fun `a catalog refresh cannot uninstall a model`() {
        val catalog = PipelineCodec.decodeCatalog(repoDir("registry").resolve("catalog.example.json").readText())
        val first = catalog.models.first()
        val registry = ModelRegistry(
            listOf(RegistryEntry(first, InstallState.INSTALLED, installedPath = "/models/text/${first.id}")),
        )

        registry.merge(catalog)

        assertEquals(InstallState.INSTALLED, registry.find(first.id)!!.state)
        assertEquals("/models/text/${first.id}", registry.find(first.id)!!.installedPath)
    }
}
