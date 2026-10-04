package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ArtifactSpec
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.FileSelector
import ai.localstudio.model.GenericFacet
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.ModelFamilyId
import ai.localstudio.model.ModelId
import ai.localstudio.model.ModelVariant
import ai.localstudio.model.RuntimeBinding
import ai.localstudio.model.Runtimes
import ai.localstudio.model.UnpackSpec
import ai.localstudio.model.VariantId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {

    private val root: File = Files.createTempDirectory("store").toFile()
    private val legacyDir: File = Files.createTempDirectory("legacy").toFile()
    private val layout = InstallLayout(root)
    private val hf = FakeHuggingFace()
    private var hashed = 0
    private val migrator = LegacyMigrator(layout, hf, clock = { 7L }, sha256 = { hashed++; Sha256.of(it) })

    private val q4 = bytesOf(50_000)
    private val q8 = bytesOf(90_000, seed = 5)
    private val mmproj = bytesOf(20_000, seed = 9)
    private val repos = listOf("first/m-GGUF", "second/m-GGUF")

    private fun publish() {
        hf.repo("first/m-GGUF", COMMIT_A, RepoFile("m-Q8_0.gguf", q8.size.toLong(), sha256(q8)))
        hf.repo(
            "second/m-GGUF", COMMIT_B,
            RepoFile("m-Q4_K_M.gguf", q4.size.toLong(), sha256(q4)),
            RepoFile("m-Q8_0.gguf", q8.size.toLong(), sha256(q8)),
            RepoFile("mmproj-F16.gguf", mmproj.size.toLong(), sha256(mmproj)),
        )
    }

    private fun model(status: CatalogStatus = CatalogStatus.UNVERIFIED, weightsSource: ArtifactSource? = null, weightsSha: String? = null) = ModelDefinition(
        ModelId("m"), ModelFamilyId("m"), "M", status = status,
        capabilities = mapOf(Capabilities.TEXT_GENERATION to GenericFacet(), Capabilities.VISION to GenericFacet(setOf(ArtifactRoles.PROJECTOR))),
        variants = listOf(
            ModelVariant(
                VariantId("m@legacy"),
                artifacts = listOf(
                    ArtifactSpec(
                        ArtifactRoles.WEIGHTS, "model.gguf", q4.size.toLong(), weightsSha,
                        weightsSource ?: ArtifactSource.HuggingFaceSelection(repos, "main", FileSelector.ByQuantization(listOf("Q4_K_M", "Q8_0"), ".gguf")),
                    ),
                    ArtifactSpec(
                        ArtifactRoles.PROJECTOR, "projector.gguf", 0, null, optional = true,
                        source = ArtifactSource.HuggingFaceSelection(repos, "main", FileSelector.ExactName("mmproj-F16.gguf")),
                    ),
                ),
                bindings = listOf(RuntimeBinding(Runtimes.LLAMA_CPP, setOf(ArtifactRoles.WEIGHTS), setOf(ArtifactRoles.PROJECTOR))),
            ),
        ),
    )

    private fun legacyFile(name: String, bytes: ByteArray) = File(legacyDir, name).apply { writeBytes(bytes) }

    private fun legacy(model: ModelDefinition = model(), weights: File? = legacyFile("m.gguf", q4), projector: File? = null) =
        LegacyInstallation(
            model, model.variants.single(),
            buildMap {
                weights?.let { put(ArtifactRoles.WEIGHTS, it) }
                projector?.let { put(ArtifactRoles.PROJECTOR, it) }
            },
            origin = "models/m.gguf",
        )

    private fun migrate(installation: LegacyInstallation = legacy()) = migrator.migrate("legacy", "1", installation)

    @Test
    fun `bytes equal to the file the selection picks are proven and adopted by hard link`() {
        publish()
        val legacyWeights = legacyFile("m.gguf", q4)
        val legacyProjector = legacyFile("m.mmproj.gguf", mmproj)
        val outcome = assertIs<MigrationOutcome.Imported>(migrate(legacy(weights = legacyWeights, projector = legacyProjector)))

        val dir = layout.variantDir(VariantId("m@legacy"))
        val adopted = File(dir, "model.gguf")
        assertTrue(Files.isSameFile(adopted.toPath(), legacyWeights.toPath()), "a hard link, not a copy")
        assertContentEquals(q4, legacyWeights.readBytes(), "the legacy file is untouched")

        val weights = outcome.manifest.artifacts.first()
        assertEquals("second/m-GGUF", weights.source.repo, "proven against the fallback repository that holds it")
        assertEquals(COMMIT_B, weights.source.commit)
        assertEquals("m-Q4_K_M.gguf", weights.source.path)
        assertEquals(IntegrityBasis.UPSTREAM_SHA256, weights.integrity)
        assertEquals(legacyWeights.path, weights.migratedFrom)
        assertEquals(setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), outcome.manifest.roles)

        val installed = InstalledVariants(layout)
        assertEquals(outcome.manifest, installed.all().single())
        assertEquals(InstallHealth.Intact, installed.verifyHashes(outcome.manifest))
        assertIs<MigrationOutcome.AlreadyInStore>(migrate(legacy(weights = legacyWeights)))
        assertTrue(migrator.records().isEmpty())
    }

    @Test
    fun `same size, different bytes is unproven - recorded, nothing installed`() {
        publish()
        val outcome = assertIs<MigrationOutcome.Unproven>(migrate(legacy(weights = legacyFile("m.gguf", bytesOf(q4.size, seed = 42)))))
        assertTrue("sha256" in outcome.record.reason, outcome.record.reason)
        assertEquals(LegacyRecord.STATUS, outcome.record.status)
        assertEquals(emptyList(), InstalledVariants(layout).all())
        assertEquals(listOf(outcome.record), migrator.records())
    }

    @Test
    fun `a size that matches no candidate is unproven without hashing`() {
        publish()
        assertIs<MigrationOutcome.Unproven>(migrate(legacy(weights = legacyFile("m.gguf", bytesOf(12_345)))))
        assertEquals(0, hashed)
    }

    @Test
    fun `bytes of a file the selection would not pick are not proof`() {
        publish()
        // q8 is in both repositories, but the selection picks Q4_K_M in second/ and
        // Q8_0 only in first/ — so q8 bytes ARE what first/ would install: proven.
        assertIs<MigrationOutcome.Imported>(migrate(legacy(weights = legacyFile("m.gguf", q8))))

        // A file the selection picks nowhere (an extra quant in second/) is not.
        hf.repo("second/m-GGUF", COMMIT_B, RepoFile("m-Q2_K.gguf", 777, sha256(bytesOf(777, seed = 3))))
        InstalledVariants(layout).uninstall(VariantId("m@legacy"))
        val outcome = assertIs<MigrationOutcome.Unproven>(migrate(legacy(weights = legacyFile("m.gguf", bytesOf(777, seed = 3)))))
        assertTrue("matches none" in outcome.record.reason, outcome.record.reason)
    }

    @Test
    fun `an unproven optional projector is left out, an unproven required file stops everything`() {
        publish()
        val withBadProjector = assertIs<MigrationOutcome.Imported>(migrate(legacy(projector = legacyFile("p.gguf", bytesOf(mmproj.size, seed = 77)))))
        assertEquals(setOf(ArtifactRoles.WEIGHTS), withBadProjector.manifest.roles)
        assertTrue(withBadProjector.manifest.skippedOptional.single().reason.startsWith("legacy file not proven"))

        InstalledVariants(layout).uninstall(VariantId("m@legacy"))
        assertIs<MigrationOutcome.Unproven>(
            migrate(legacy(weights = legacyFile("m.gguf", bytesOf(q4.size, seed = 8)), projector = legacyFile("p.gguf", mmproj))),
        )
        assertEquals(emptyList(), InstalledVariants(layout).all(), "a proven projector alone imports nothing")
    }

    @Test
    fun `an unpacked directory has nothing left to prove`() {
        val archiveModel = model().let { m ->
            m.copy(
                variants = listOf(
                    m.variants.single().copy(
                        artifacts = listOf(
                            ArtifactSpec(ArtifactRoles.ARCHIVE, "model.zip", 0, null, ArtifactSource.DirectUrl("https://alphacephei.com/m.zip"), unpack = UnpackSpec("zip", stripComponents = 1)),
                        ),
                        bindings = listOf(RuntimeBinding(Runtimes.VOSK, setOf(ArtifactRoles.ARCHIVE))),
                    ),
                ),
            )
        }
        val dir = File(legacyDir, "vosk-small-ru").apply { mkdirs() }
        File(dir, "am").mkdirs()
        File(dir, "am/final.mdl").writeText("AM")
        val outcome = assertIs<MigrationOutcome.Unproven>(
            migrate(LegacyInstallation(archiveModel, archiveModel.variants.single(), mapOf(ArtifactRoles.ARCHIVE to dir), "vosk-models/vosk-small-ru")),
        )
        assertTrue("archive is gone" in outcome.record.reason)
        assertTrue(outcome.record.files.single().isDirectory)
        assertEquals(2, outcome.record.files.single().sizeBytes)
    }

    @Test
    fun `no upstream hash means no proof and no hashing`() {
        hf.repo("first/m-GGUF", COMMIT_A, RepoFile("m-Q4_K_M.gguf", q4.size.toLong(), lfsSha256 = null))
        val outcome = assertIs<MigrationOutcome.Unproven>(migrate())
        assertTrue("no upstream file with a known sha256" in outcome.record.reason, outcome.record.reason)
        assertEquals(0, hashed)
    }

    @Test
    fun `a network failure defers the decision and writes nothing`() {
        hf.failures["first/m-GGUF"] = SourceException(SourceException.Kind.NETWORK, "offline")
        assertIs<MigrationOutcome.Deferred>(migrate())
        assertTrue(migrator.records().isEmpty())
        assertFalse(layout.variantDir(VariantId("m@legacy")).exists())
    }

    @Test
    fun `a catalogue sha256 proves a direct-url file without any upstream metadata`() {
        val verified = model(
            status = CatalogStatus.VERIFIED,
            weightsSource = ArtifactSource.DirectUrl("https://example.org/m.gguf"),
            weightsSha = sha256(q4),
        )
        val outcome = assertIs<MigrationOutcome.Imported>(migrate(legacy(model = verified)))
        assertEquals(IntegrityBasis.CATALOG_SHA256, outcome.manifest.artifacts.single().integrity)
        assertEquals(emptyList(), hf.calls, "a direct URL with a catalogue hash needs no Hugging Face lookup")
    }

    @Test
    fun `a record is cleared once the installation is later proven`() {
        hf.repo("first/m-GGUF", COMMIT_A, RepoFile("m-Q4_K_M.gguf", q4.size.toLong(), lfsSha256 = null))
        hf.repo("second/m-GGUF", COMMIT_B)
        assertIs<MigrationOutcome.Unproven>(migrate())
        assertEquals(1, migrator.records().size)

        hf.repo("second/m-GGUF", COMMIT_B, RepoFile("m-Q4_K_M.gguf", q4.size.toLong(), sha256(q4)))
        assertIs<MigrationOutcome.Imported>(migrate())
        assertTrue(migrator.records().isEmpty())
        assertNull(InstalledVariants(layout).manifest(VariantId("m@legacy"))?.skippedOptional?.firstOrNull(), "no projector file: nothing to skip")
    }
}
