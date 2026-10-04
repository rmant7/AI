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
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MigrationTest {

    private val root: File = Files.createTempDirectory("store").toFile()
    private val legacyDir: File = Files.createTempDirectory("legacy").toFile()
    private val layout = InstallLayout(root)
    private val hf = FakeHuggingFace()
    private var hashed = 0
    private var moveWorks = true
    private var copyFails = false
    private val migrator = LegacyMigrator(
        layout, hf, clock = { 7L }, sha256 = { hashed++; Sha256.of(it) },
        move = { from, to -> moveWorks && from.renameTo(to) },
        copy = { from, to -> if (copyFails) throw IOException("disk full") else from.copyTo(to, overwrite = true) },
    )
    private val variantId = VariantId("m@legacy")

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
                variantId,
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

    private fun adopt(installation: LegacyInstallation) = migrator.adopt("legacy", "1", installation)

    // --- migrate(): proof only ---

    @Test
    fun `migrate proves and records but moves nothing`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val projector = legacyFile("m.mmproj.gguf", mmproj)
        val record = assertIs<MigrationOutcome.Proven>(migrator.migrate(legacy(weights = weights, projector = projector))).record

        assertEquals(LegacyRecord.PROVEN, record.status)
        val proof = record.proofs.first { it.role == ArtifactRoles.WEIGHTS }
        assertEquals("second/m-GGUF", proof.source.repo, "proven against the fallback repository that holds it")
        assertEquals(COMMIT_B, proof.source.commit)
        assertEquals("m-Q4_K_M.gguf", proof.source.path)
        assertEquals(sha256(q4), proof.sha256)
        assertEquals(weights.length(), proof.sizeBytes)
        assertEquals(weights.lastModified(), proof.lastModifiedMs)
        assertEquals(setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), record.proofs.map { it.role }.toSet())

        assertContentEquals(q4, weights.readBytes(), "the legacy file is untouched")
        assertTrue(projector.isFile)
        assertEquals(emptyList(), InstalledVariants(layout).all(), "proof is not an install")
        assertEquals(listOf(record), migrator.records())
    }

    @Test
    fun `same size, different bytes is unproven`() {
        publish()
        val outcome = assertIs<MigrationOutcome.Unproven>(migrator.migrate(legacy(weights = legacyFile("m.gguf", bytesOf(q4.size, seed = 42)))))
        assertTrue("sha256" in outcome.record.reason!!, outcome.record.reason)
        assertEquals(LegacyRecord.UNVERIFIED, outcome.record.status)
        assertTrue(outcome.record.proofs.isEmpty())
    }

    @Test
    fun `a size that matches no candidate is unproven without hashing`() {
        publish()
        assertIs<MigrationOutcome.Unproven>(migrator.migrate(legacy(weights = legacyFile("m.gguf", bytesOf(12_345)))))
        assertEquals(0, hashed)
    }

    @Test
    fun `bytes of a file the selection would not pick are not proof`() {
        publish()
        // q8 is what first/ would install (its only quant): proven.
        assertIs<MigrationOutcome.Proven>(migrator.migrate(legacy(weights = legacyFile("m.gguf", q8))))
        // An extra quant the selection picks nowhere is not.
        hf.repo("second/m-GGUF", COMMIT_B, RepoFile("m-Q2_K.gguf", 777, sha256(bytesOf(777, seed = 3))))
        val outcome = assertIs<MigrationOutcome.Unproven>(migrator.migrate(legacy(weights = legacyFile("m.gguf", bytesOf(777, seed = 3)))))
        assertTrue("matches none" in outcome.record.reason!!)
    }

    @Test
    fun `an unproven optional projector is left out, an unproven required file decides everything`() {
        publish()
        val record = assertIs<MigrationOutcome.Proven>(migrator.migrate(legacy(projector = legacyFile("p.gguf", bytesOf(mmproj.size, seed = 77))))).record
        assertEquals(listOf(ArtifactRoles.WEIGHTS), record.proofs.map { it.role })
        assertTrue(record.skippedOptional.single().reason.startsWith("legacy file not proven"))

        assertIs<MigrationOutcome.Unproven>(
            migrator.migrate(legacy(weights = legacyFile("m.gguf", bytesOf(q4.size, seed = 8)), projector = legacyFile("p.gguf", mmproj))),
        )
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
        val installation = LegacyInstallation(archiveModel, archiveModel.variants.single(), mapOf(ArtifactRoles.ARCHIVE to dir), "vosk-models/vosk-small-ru")
        val outcome = assertIs<MigrationOutcome.Unproven>(migrator.migrate(installation))
        assertTrue("archive is gone" in outcome.record.reason!!)
        assertEquals(2, outcome.record.files.single().sizeBytes)
        assertIs<AdoptionOutcome.NotProven>(adopt(installation))
        assertTrue(File(dir, "am/final.mdl").isFile, "an unproven installation is never moved")
    }

    @Test
    fun `no upstream hash means no proof and no hashing`() {
        hf.repo("first/m-GGUF", COMMIT_A, RepoFile("m-Q4_K_M.gguf", q4.size.toLong(), lfsSha256 = null))
        val outcome = assertIs<MigrationOutcome.Unproven>(migrator.migrate(legacy()))
        assertTrue("no upstream file with a known sha256" in outcome.record.reason!!)
        assertEquals(0, hashed)
    }

    @Test
    fun `a network failure defers the decision and writes nothing`() {
        hf.failures["first/m-GGUF"] = SourceException(SourceException.Kind.NETWORK, "offline")
        assertIs<MigrationOutcome.Deferred>(migrator.migrate(legacy()))
        assertTrue(migrator.records().isEmpty())
    }

    @Test
    fun `a catalogue sha256 proves a direct-url file without any upstream metadata`() {
        val verified = model(status = CatalogStatus.VERIFIED, weightsSource = ArtifactSource.DirectUrl("https://example.org/m.gguf"), weightsSha = sha256(q4))
        val record = assertIs<MigrationOutcome.Proven>(migrator.migrate(legacy(model = verified))).record
        assertEquals(IntegrityBasis.CATALOG_SHA256, record.proofs.single().integrity)
        assertEquals(emptyList(), hf.calls)
    }

    // --- adopt(): the move, at switch time ---

    @Test
    fun `adopt renames the proven files into the store, no copy, no leftovers`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val projector = legacyFile("m.mmproj.gguf", mmproj)
        val installation = legacy(weights = weights, projector = projector)
        assertIs<MigrationOutcome.Proven>(migrator.migrate(installation))
        val inode = Files.getAttribute(weights.toPath(), "unix:ino")
        hashed = 0

        val manifest = assertIs<AdoptionOutcome.Adopted>(adopt(installation)).manifest
        val adopted = File(layout.variantDir(variantId), "model.gguf")
        assertEquals(inode, Files.getAttribute(adopted.toPath(), "unix:ino"), "a rename: the same inode, no second copy")
        assertFalse(weights.exists() || projector.exists(), "the legacy paths are gone: the bytes have one owner")
        assertEquals(0, hashed, "unchanged since the proof: no re-hash")
        assertEquals(weights.path, manifest.artifacts.first().migratedFrom)
        assertEquals(COMMIT_B, manifest.artifacts.first().source.commit)
        assertEquals(InstallHealth.Intact, InstalledVariants(layout).verifyHashes(manifest))
        assertTrue(migrator.records().isEmpty(), "the proof is consumed")
        assertIs<AdoptionOutcome.AlreadyInStore>(adopt(installation))
    }

    @Test
    fun `adopt re-proves a file whose size or mtime changed since the proof`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val installation = legacy(weights = weights)
        assertIs<MigrationOutcome.Proven>(migrator.migrate(installation))

        // Same size, different bytes, newer mtime: the legacy code re-downloaded something else.
        weights.writeBytes(bytesOf(q4.size, seed = 99))
        weights.setLastModified(weights.lastModified() + 5_000)
        val outcome = assertIs<AdoptionOutcome.NotProven>(adopt(installation))
        assertIs<MigrationOutcome.Unproven>(outcome.outcome)
        assertTrue(weights.isFile, "nothing moved")
        assertEquals(emptyList(), InstalledVariants(layout).all())

        // mtime touched, bytes the same: re-proven, then adopted.
        weights.writeBytes(q4)
        weights.setLastModified(weights.lastModified() + 10_000)
        hashed = 0
        assertIs<AdoptionOutcome.Adopted>(adopt(installation))
        assertEquals(1, hashed, "re-proved once")
    }

    @Test
    fun `adopt without a prior proof proves first`() {
        publish()
        assertIs<AdoptionOutcome.Adopted>(adopt(legacy()))
    }

    @Test
    fun `when rename is impossible the file is copied, verified, and the original removed only after`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val installation = legacy(weights = weights)
        migrator.migrate(installation)
        moveWorks = false
        val manifest = assertIs<AdoptionOutcome.Adopted>(adopt(installation)).manifest
        assertContentEquals(q4, File(layout.variantDir(variantId), "model.gguf").readBytes())
        assertFalse(weights.exists())
        assertEquals(sha256(q4), manifest.artifacts.single().sha256)
    }

    @Test
    fun `a failure half way puts every legacy file back`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val projector = legacyFile("m.mmproj.gguf", mmproj)
        val installation = legacy(weights = weights, projector = projector)
        migrator.migrate(installation)
        // Weights rename fine; the projector can't be renamed and its copy fails.
        var calls = 0
        val flaky = LegacyMigrator(
            layout, hf, clock = { 7L },
            move = { from, to -> (calls++ == 0) && from.renameTo(to) },
            copy = { _, _ -> throw IOException("disk full") },
        )
        assertIs<AdoptionOutcome.Deferred>(flaky.adopt("legacy", "1", installation))
        assertContentEquals(q4, weights.readBytes(), "the renamed file is back")
        assertContentEquals(mmproj, projector.readBytes())
        assertEquals(emptyList(), InstalledVariants(layout).all())
        assertFalse(layout.migrationStagingDir(variantId).exists())
        assertEquals(LegacyRecord.PROVEN, flaky.record(variantId)?.status, "the proof survives for a retry")
        assertIs<AdoptionOutcome.Adopted>(adopt(installation))
    }

    @Test
    fun `on the copy path a legacy original survives until the whole install is in place`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val projector = legacyFile("m.mmproj.gguf", mmproj)
        val installation = legacy(weights = weights, projector = projector)
        migrator.migrate(installation)
        // No renames at all; the weights copy works, the projector copy fails.
        var copies = 0
        val copying = LegacyMigrator(
            layout, hf, clock = { 7L },
            move = { _, _ -> false },
            copy = { from, to -> if (copies++ == 0) from.copyTo(to, overwrite = true) else throw IOException("disk full") },
        )
        assertIs<AdoptionOutcome.Deferred>(copying.adopt("legacy", "1", installation))
        assertContentEquals(q4, weights.readBytes(), "copied already, but the install never landed: the original stays")
        assertContentEquals(mmproj, projector.readBytes())
        assertEquals(emptyList(), InstalledVariants(layout).all())
    }

    @Test
    fun `a copy that does not match the proven sha256 is rejected`() {
        publish()
        val weights = legacyFile("m.gguf", q4)
        val installation = legacy(weights = weights)
        migrator.migrate(installation)
        val corrupting = LegacyMigrator(
            layout, hf, clock = { 7L },
            move = { _, _ -> false },
            copy = { _, to -> to.writeBytes(bytesOf(q4.size, seed = 13)) },
        )
        val outcome = assertIs<AdoptionOutcome.Deferred>(corrupting.adopt("legacy", "1", installation))
        assertTrue("sha256" in outcome.reason, outcome.reason)
        assertContentEquals(q4, weights.readBytes())
        assertEquals(emptyList(), InstalledVariants(layout).all())
    }
}
