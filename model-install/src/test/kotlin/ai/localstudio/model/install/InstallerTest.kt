package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CatalogLoader
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.CatalogTrust
import ai.localstudio.model.GenericFacet
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelFamilyId
import ai.localstudio.model.ModelId
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.RuntimeBinding
import ai.localstudio.model.Runtimes
import ai.localstudio.model.UnpackSpec
import ai.localstudio.model.VariantId
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallerTest {

    private val root: File = Files.createTempDirectory("models").toFile()
    private val layout = InstallLayout(root)
    private val hf = FakeHuggingFace()
    private val transport = FakeTransport()
    private val installer = ModelInstaller(layout, ArtifactResolver(hf), TransferEngine(transport, sleep = noSleep), clock = { 1_000L })
    private val installed = InstalledVariants(layout)
    private val plenty: () -> Long = { Long.MAX_VALUE / 2 }

    private val weightsBytes = bytesOf(150_000)
    private val projectorBytes = bytesOf(40_000, seed = 3)

    private fun gemmaLike(projectorPath: String = "mmproj-F16.gguf", status: CatalogStatus = CatalogStatus.UNVERIFIED): ModelDefinition {
        hf.repo("org/m-GGUF", COMMIT_A, RepoFile("m-Q4_K_M.gguf", weightsBytes.size.toLong(), sha256(weightsBytes)), RepoFile("mmproj-F16.gguf", projectorBytes.size.toLong(), sha256(projectorBytes)))
        transport.bodies[ArtifactResolver.resolveUrl("org/m-GGUF", COMMIT_A, "m-Q4_K_M.gguf")] = weightsBytes
        transport.bodies[ArtifactResolver.resolveUrl("org/m-GGUF", COMMIT_A, "mmproj-F16.gguf")] = projectorBytes
        val repos = listOf("org/m-GGUF")
        return ModelDefinition(
            id = ModelId("m"),
            family = ModelFamilyId("m"),
            displayName = "M",
            status = status,
            capabilities = mapOf(
                Capabilities.TEXT_GENERATION to GenericFacet(),
                Capabilities.VISION to GenericFacet(setOf(ArtifactRoles.PROJECTOR)),
            ),
            variants = listOf(
                ModelVariant(
                    VariantId("m@legacy"),
                    artifacts = listOf(
                        ArtifactSpec(
                            ArtifactRoles.PROJECTOR, "projector.gguf", 0, null, optional = true,
                            source = ArtifactSource.HuggingFaceSelection(repos, "main", ai.localstudio.model.FileSelector.ExactName(projectorPath)),
                        ),
                        ArtifactSpec(
                            ArtifactRoles.WEIGHTS, "model.gguf", 999, null,
                            ArtifactSource.HuggingFaceSelection(repos, "main", ai.localstudio.model.FileSelector.ByQuantization(listOf("Q4_K_M"), ".gguf")),
                        ),
                    ),
                    bindings = listOf(RuntimeBinding(Runtimes.LLAMA_CPP, setOf(ArtifactRoles.WEIGHTS), setOf(ArtifactRoles.PROJECTOR))),
                ),
            ),
        )
    }

    private fun install(model: ModelDefinition, free: () -> Long = plenty) =
        installer.install("test", "1", model, model.variants.single(), free)

    @Test
    fun `a variant installs complete, verified, with a manifest saying where every byte came from`() {
        val model = gemmaLike()
        val result = assertIs<InstallResult.Installed>(install(model))
        val dir = layout.variantDir(VariantId("m@legacy"))
        assertContentEquals(weightsBytes, File(dir, "model.gguf").readBytes())
        assertContentEquals(projectorBytes, File(dir, "projector.gguf").readBytes())
        assertFalse(layout.stagingDir(VariantId("m@legacy")).exists(), "staging is consumed by the move into place")

        val manifest = installed.manifest(VariantId("m@legacy"))!!
        assertEquals(result.manifest, manifest)
        assertEquals(listOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), manifest.artifacts.map { it.role }, "mandatory first")
        val weights = manifest.artifacts.first()
        assertEquals(sha256(weightsBytes), weights.sha256)
        assertEquals(IntegrityBasis.UPSTREAM_SHA256, weights.integrity)
        assertEquals(COMMIT_A, weights.source.commit)
        assertEquals("main", weights.source.requestedRevision)
        assertEquals("m-Q4_K_M.gguf", weights.source.path)
        assertEquals(InstallHealth.Intact, installed.health(manifest))
        assertEquals(InstallHealth.Intact, installed.verifyHashes(manifest))
    }

    @Test
    fun `a missing optional projector leaves a working text-only install, recorded as skipped`() {
        val model = gemmaLike(projectorPath = "mmproj-renamed.gguf")
        val manifest = assertIs<InstallResult.Installed>(install(model)).manifest
        assertEquals(setOf(ArtifactRoles.WEIGHTS), manifest.roles)
        assertEquals("projector.gguf", manifest.skippedOptional.single().fileName)

        val device = DeviceProfile(androidApi = 34, runtimes = setOf(Runtimes.LLAMA_CPP))
        val choice = assertIs<RuntimeChoice.Chosen>(BindingSelector.choose(model.variants.single(), manifest.roles, device))
        assertEquals(emptySet(), choice.optionalRoles, "no projector: vision unavailable, text works")
    }

    @Test
    fun `a failed required download leaves no variant and keeps staging to resume`() {
        val model = gemmaLike()
        val weightsUrl = ArtifactResolver.resolveUrl("org/m-GGUF", COMMIT_A, "m-Q4_K_M.gguf")
        transport.cutAfter[weightsUrl] = ArrayDeque(List(3) { 40_000 })
        val failed = assertIs<InstallResult.Failed>(install(model))
        assertEquals("model.gguf", failed.fileName)
        assertFalse(layout.variantDir(VariantId("m@legacy")).exists())
        assertTrue(File(layout.stagingDir(VariantId("m@legacy")), "model.gguf.part").length() > 0)

        transport.opens.clear()
        assertIs<InstallResult.Installed>(install(model))
        assertTrue(transport.opens.first { it.first == weightsUrl }.second > 0, "the retry resumed the kept part")
    }

    @Test
    fun `not enough storage stops before a single byte is downloaded`() {
        val result = assertIs<InstallResult.InsufficientStorage>(install(gemmaLike(), free = { 100_000 }))
        assertEquals(weightsBytes.size + projectorBytes.size + ResourceAdmission.STORAGE_SLACK_BYTES, result.neededBytes)
        assertTrue(transport.opens.isEmpty())
    }

    @Test
    fun `deprecated and withdrawn models are not newly installed`() {
        assertEquals(InstallResult.Refused(CatalogStatus.WITHDRAWN), install(gemmaLike(status = CatalogStatus.WITHDRAWN)))
        assertEquals(InstallResult.Refused(CatalogStatus.DEPRECATED), install(gemmaLike(status = CatalogStatus.DEPRECATED)))
    }

    @Test
    fun `reinstalling replaces the old directory, uninstalling removes it`() {
        val model = gemmaLike()
        install(model)
        val dir = layout.variantDir(VariantId("m@legacy"))
        File(dir, "stale.tmp").writeText("old")
        assertIs<InstallResult.Installed>(install(model))
        assertFalse(File(dir, "stale.tmp").exists())
        assertEquals(1, installed.all().size)

        File(dir, "projector.gguf").delete()
        assertIs<InstallHealth.Damaged>(installed.health(installed.all().single()))

        assertTrue(installed.uninstall(VariantId("m@legacy")))
        assertFalse(dir.exists())
        assertEquals(emptyList(), installed.all())
    }

    // --- the Phase 2 catalogue, end to end ---

    private fun legacyCatalog() =
        CatalogLoader.load(File(System.getProperty("localai.legacyCatalog")).readText(), CatalogTrust.Bundled).also {
            assertEquals(emptyList(), it.violations)
        }

    @Test
    fun `the legacy gemma entry installs through the new chain`() {
        val gemma = legacyCatalog().models.single { it.id.id == "gemma-4-e4b-it-q4" }
        val repo = "unsloth/gemma-4-E4B-it-GGUF"
        hf.repo(repo, COMMIT_B, RepoFile("gemma-4-E4B-it-Q4_K_M.gguf", weightsBytes.size.toLong(), sha256(weightsBytes)), RepoFile("mmproj-F16.gguf", projectorBytes.size.toLong(), sha256(projectorBytes)))
        transport.bodies[ArtifactResolver.resolveUrl(repo, COMMIT_B, "gemma-4-E4B-it-Q4_K_M.gguf")] = weightsBytes
        transport.bodies[ArtifactResolver.resolveUrl(repo, COMMIT_B, "mmproj-F16.gguf")] = projectorBytes

        val manifest = assertIs<InstallResult.Installed>(installer.install("local-models-legacy", "legacy-mapping-1", gemma, gemma.variants.single(), plenty)).manifest
        assertEquals(setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), manifest.roles)
        assertTrue(manifest.artifacts.all { it.source.commit == COMMIT_B && it.integrity == IntegrityBasis.UPSTREAM_SHA256 })
    }

    @Test
    fun `the legacy vosk entry falls back to its alternative and unpacks without the wrapper folder`() {
        val vosk = legacyCatalog().models.single { it.id.id == "vosk-small-en" }
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("vosk-model-small-en-us-0.15/am/final.mdl"))
                out.write("AM".toByteArray())
                out.closeEntry()
            }
        }.toByteArray()
        hf.repo("grimso/vosk-models", COMMIT_A, RepoFile("vosk-model-small-en-us-0.15.zip", zip.size.toLong(), null))
        transport.bodies[ArtifactResolver.resolveUrl("grimso/vosk-models", COMMIT_A, "vosk-model-small-en-us-0.15.zip")] = zip
        // alphacephei answers 404: not in transport.bodies.

        val result = assertIs<InstallResult.Installed>(installer.install("local-models-legacy", "legacy-mapping-1", vosk, vosk.variants.single(), plenty))
        val archive = result.manifest.artifacts.single()
        assertEquals("model", archive.unpackedDir)
        assertEquals(IntegrityBasis.SIZE_ONLY, archive.integrity, "no hash anywhere for this file: size only, and the manifest says so")
        assertEquals("AM", File(installed.pathOf(result.manifest, archive), "am/final.mdl").readText())
        assertFalse(File(layout.variantDir(vosk.variants.single().id), "model.zip").exists(), "the archive is not kept")
        assertTrue(result.log.any { it.source.contains("alphacephei") })
        assertEquals(InstallHealth.Intact, installed.health(result.manifest))
    }
}
