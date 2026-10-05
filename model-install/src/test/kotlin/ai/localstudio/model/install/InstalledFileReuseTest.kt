package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ModelId
import ai.localstudio.model.RuntimeId
import ai.localstudio.model.VariantId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledFileReuseTest {

    private val root = Files.createTempDirectory("reuse").toFile()
    private val layout = InstallLayout(root)
    private val installed = InstalledVariants(layout)

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val commit = "a".repeat(40)
    private val weightsBytes = "weights".repeat(1000).toByteArray()
    private val weightsSha = Sha256.of(File.createTempFile("weights", null).apply { writeBytes(weightsBytes); deleteOnExit() })

    /** An installed variant holding the weights file, as the installer leaves one. */
    private fun install(variant: String, repo: String = "acme/see-GGUF") {
        val dir = layout.variantDir(VariantId(variant)).apply { mkdirs() }
        File(dir, "model.gguf").writeBytes(weightsBytes)
        val manifest = InstallManifest(
            catalogId = "discovered", catalogVersion = commit, modelId = ModelId("discovered-x"), variantId = VariantId(variant),
            installedAtEpochMs = 1,
            artifacts = listOf(
                InstalledArtifact(
                    role = ArtifactRoles.WEIGHTS, fileName = "model.gguf", sizeBytes = weightsBytes.size.toLong(), sha256 = weightsSha,
                    integrity = IntegrityBasis.CATALOG_SHA256,
                    source = SourceRecord(url = "https://huggingface.co/$repo/resolve/$commit/see-Q4_K_M.gguf", repo = repo, commit = commit, path = "see-Q4_K_M.gguf"),
                ),
            ),
        )
        File(dir, InstallManifest.FILE_NAME).writeText(ManifestCodec.encode(manifest))
    }

    private val pair = CandidateModel.of(
        ModelArtifact(
            ModelFile("acme/see-GGUF", commit, "see-Q4_K_M.gguf", weightsBytes.size.toLong(), weightsSha),
            ProjectorFile(ModelFile("acme/see-GGUF", commit, "mmproj-F16.gguf", 10, "f".repeat(64)), "gemma3"),
        ),
        "discovered-acme_see-gguf-aaaaaaaaaaaa-mm",
        RuntimeId("llama_cpp"),
    ).variants.single()

    @Test
    fun the_same_models_earlier_install_gives_its_weights_to_the_pair_and_goes() {
        install("discovered-acme_see-gguf-aaaaaaaaaaaa")

        val reused = InstalledFileReuse.seed(layout, pair, mayMove = { _, _ -> true })

        assertEquals(listOf(true), reused.map { it.moved })
        assertEquals(ArtifactRoles.WEIGHTS, reused.single().role)
        val staged = File(layout.stagingDir(pair.id), "model.gguf")
        assertTrue(staged.readBytes().contentEquals(weightsBytes))
        assertNull(installed.manifest(VariantId("discovered-acme_see-gguf-aaaaaaaaaaaa")), "superseded: it no longer has its file")
        assertFalse(File(layout.stagingDir(pair.id), "projector.gguf").exists(), "nothing installed has the projector: it is downloaded")
    }

    @Test
    fun a_model_in_use_keeps_its_file_and_the_pair_gets_a_copy() {
        install("custom-see-legacy")

        val reused = InstalledFileReuse.seed(layout, pair, mayMove = { _, _ -> false })

        assertEquals(listOf(false), reused.map { it.moved })
        assertTrue(File(layout.variantDir(VariantId("custom-see-legacy")), "model.gguf").isFile)
        assertTrue(File(layout.stagingDir(pair.id), "model.gguf").readBytes().contentEquals(weightsBytes))
        assertEquals(InstallHealth.Intact, installed.health(installed.manifest(VariantId("custom-see-legacy"))!!))
    }

    @Test
    fun only_the_same_hash_is_reused() {
        install("discovered-other")
        val other = pair.copy(artifacts = pair.artifacts.map { if (it.role == ArtifactRoles.WEIGHTS) it.copy(sha256 = "0".repeat(64)) else it })

        assertEquals(emptyList(), InstalledFileReuse.seed(layout, other, mayMove = { _, _ -> true }))
        assertTrue(installed.manifest(VariantId("discovered-other")) != null)
    }
}
