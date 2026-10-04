package ai.localstudio.app.modelinstall

import ai.localstudio.app.llama.EmbeddingPooling
import ai.localstudio.app.llama.LlamaBridge
import ai.localstudio.app.llama.LlamaCppMemoryEmbedder
import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CatalogLoader
import ai.localstudio.model.CatalogTrust
import ai.localstudio.model.EmbeddingFacet
import ai.localstudio.model.Runtimes
import ai.localstudio.model.install.Admission
import ai.localstudio.model.install.BindingSelector
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.InstallResult
import ai.localstudio.model.install.ResourceAdmission
import ai.localstudio.model.install.RuntimeChoice
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sqrt

/**
 * catalog → resolve → download → verify → install → discover → runtime →
 * inference, for real: the generated catalogue's multilingual-e5-small
 * entry, fetched from huggingface.co over the device's network, installed
 * by the new chain into a scratch root, found again by a fresh instance from
 * disk, run through llama.cpp's embedding path.
 *
 * Needs network. A failure here with a NETWORK kind in the message is the
 * emulator's connectivity, not the chain — but it stays a failure: this is
 * the one test that proves the chain against the real Hugging Face.
 */
@RunWith(AndroidJUnit4::class)
class ModelInstallEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val root = File(context.cacheDir, "model-install-e2e")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun the_legacy_e5_entry_installs_from_huggingface_and_embeds() {
        val catalogText = testAssets.open("local-models.json").bufferedReader().use { it.readText() }
        val catalog = CatalogLoader.load(catalogText, CatalogTrust.Bundled)
        assertEquals(emptyList<Any>(), catalog.violations)
        val model = catalog.models.single { it.id.id == "multilingual-e5-small-iq4xs" }
        val variant = model.variants.single()
        val facet = model.facet(Capabilities.TEXT_EMBEDDING) as EmbeddingFacet

        root.deleteRecursively()
        val installation = ModelInstallation(context, root = root)
        val result = installation.installer.install(catalog.catalogId!!, catalog.catalogVersion!!, model, variant, installation::freeBytes)
        assertTrue("install failed: $result", result is InstallResult.Installed)
        val installedManifest = (result as InstallResult.Installed).manifest
        val weights = installedManifest.artifacts.single()
        assertTrue("downloaded from a commit, not a branch: ${weights.source}", Regex("^[0-9a-f]{40}$").matches(weights.source.commit ?: ""))
        android.util.Log.i(TAG, "installed ${weights.source.repo}/${weights.source.path}@${weights.source.commit}: ${weights.sizeBytes} bytes, ${weights.integrity}")

        // Discovery: a fresh instance, nothing but the disk.
        val fresh = ModelInstallation(context, root = root)
        val manifest = fresh.installed.all().single()
        assertEquals(installedManifest, manifest)
        assertEquals(InstallHealth.Intact, fresh.installed.verifyHashes(manifest))

        val choice = BindingSelector.choose(variant, manifest.roles, fresh.probe.profile(setOf(Runtimes.LLAMA_CPP)))
        assertTrue("no runnable binding: $choice", choice is RuntimeChoice.Chosen)
        val memory = ResourceAdmission.memory(variant, (choice as RuntimeChoice.Chosen).binding.requiredRoles, manifest.sizesByRole, fresh.probe.availableMemoryBytes())
        assertEquals(Admission.Admit, memory)

        val path = fresh.installed.pathOf(manifest, manifest.artifacts.single { it.role == ArtifactRoles.WEIGHTS })
        val embedder = runBlocking {
            LlamaCppMemoryEmbedder.load(
                bridge = LlamaBridge(),
                modelPath = path.absolutePath,
                modelId = model.id.id,
                pooling = EmbeddingPooling.valueOf(facet.pooling.uppercase()),
                queryPrefix = facet.queryPrefix,
                passagePrefix = facet.documentPrefix,
            )
        }
        requireNotNull(embedder) { "llama.cpp could not load ${path.absolutePath}" }
        try {
            assertEquals("the catalogue's dimensions match the model's", facet.dimensions, embedder.dimension)
            val query = runBlocking { embedder.embedForQuery("рецепты низкокалорийных десертов") }
            val (similar, unrelated) = runBlocking {
                embedder.embedForStorage(listOf("Пользователь искал рецепты низкокалорийных десертов", "Решили использовать Kotlin для нового модуля"))
            }.let { it[0] to it[1] }
            val norm = sqrt(query.sumOf { (it * it).toDouble() })
            assertEquals("facet says normalized", 1.0, norm, 1e-3)
            assertTrue("a near-duplicate must outrank an unrelated sentence", cosine(query, similar) > cosine(query, unrelated))
        } finally {
            embedder.close()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private companion object {
        const val TAG = "ModelInstallE2E"
    }
}
