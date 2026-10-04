package ai.localstudio.app.modelinstall

import ai.localstudio.model.CatalogLoader
import ai.localstudio.model.CatalogTrust
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.IntegrityBasis
import ai.localstudio.model.install.LegacyRecord
import ai.localstudio.model.install.MigrationOutcome
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 3b.2 on a device: a legacy file layout (the four legacy stores) in a
 * scratch directory, the generated catalogue, [LocalHub] answering as
 * Hugging Face — scanned, proven or rejected, adopted by hard link on the
 * device's real filesystem.
 */
@RunWith(AndroidJUnit4::class)
class LegacyMigrationDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val base = File(context.cacheDir, "legacy-migration-${System.nanoTime()}")
    private val store = File(base, "model-store")

    private val models: List<ModelDefinition> = InstrumentationRegistry.getInstrumentation().context.assets
        .open("local-models.json").bufferedReader().use { CatalogLoader.load(it.readText(), CatalogTrust.Bundled) }.models

    private val whisperBytes = ByteArray(700_000) { (it * 13 % 251).toByte() }
    private val e5Bytes = ByteArray(900_000) { (it * 7 % 241).toByte() }

    private fun installation() = ModelInstallation(
        context,
        root = store,
        hub = HuggingFaceApiClient(apiBase = hub.base),
        transport = HttpRangeTransport(rewrite = hub.rewrite),
    )

    private fun legacy(path: String, bytes: ByteArray) = File(base, path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun inode(file: File) = Os.stat(file.path).st_ino

    private fun MigrationOutcome.imported(): MigrationOutcome.Imported =
        this as? MigrationOutcome.Imported ?: throw AssertionError("expected Imported, got $this")

    @After
    fun tearDown() {
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun legacy_stores_are_scanned_proven_or_recorded_and_adopted_by_hard_link() {
        hub.publish("ggerganov/whisper.cpp", "main", "c".repeat(40), "ggml-base.bin" to whisperBytes)
        hub.publish("cstr/multilingual-e5-small-GGUF", "main", "d".repeat(40), "multilingual-e5-small-iq4_xs.gguf" to e5Bytes)

        val whisper = legacy("whisper/whisper-base.bin", whisperBytes)
        val e5 = legacy("experimental_embeddings/multilingual-e5-small-iq4xs.gguf", e5Bytes)
        // Right name, wrong bytes: the name is only a claim.
        legacy("models/gemma-3-4b-it-q4.gguf", ByteArray(4096) { 1 })
        legacy("vosk-models/vosk-small-ru/am/final.mdl", ByteArray(10) { 2 })
        legacy("models/not-a-catalogue-model.gguf", ByteArray(10))
        legacy("models/gemma-3-1b-it-q4.gguf.part", ByteArray(10))

        val scan = LegacyInstallationScanner(base, models).scan()
        assertEquals(
            listOf("experimental_embeddings/multilingual-e5-small-iq4xs.gguf", "models/gemma-3-4b-it-q4.gguf", "vosk-models/vosk-small-ru/", "whisper/whisper-base.bin"),
            scan.installations.map { it.origin },
        )
        assertEquals(listOf("not-a-catalogue-model.gguf"), scan.unrecognized.map { it.name })

        val installation = installation()
        val outcomes = scan.installations.associate { it.origin to installation.migrator.migrate("local-models-legacy", "legacy-mapping-1", it) }

        val whisperImport = outcomes.getValue("whisper/whisper-base.bin").imported()
        val adoptedWhisper = installation.installed.pathOf(whisperImport.manifest, whisperImport.manifest.artifacts.single())
        assertEquals("hard link: same inode, no second copy", inode(whisper), inode(adoptedWhisper))
        assertEquals("c".repeat(40), whisperImport.manifest.artifacts.single().source.commit)
        assertEquals(IntegrityBasis.UPSTREAM_SHA256, whisperImport.manifest.artifacts.single().integrity)
        assertArrayEquals("the legacy path still works for the legacy code", whisperBytes, whisper.readBytes())

        val e5Import = outcomes.getValue("experimental_embeddings/multilingual-e5-small-iq4xs.gguf").imported()
        assertEquals("multilingual-e5-small-iq4_xs.gguf", e5Import.manifest.artifacts.single().source.path)
        assertEquals(inode(e5), inode(installation.installed.pathOf(e5Import.manifest, e5Import.manifest.artifacts.single())))

        val gemma = outcomes.getValue("models/gemma-3-4b-it-q4.gguf")
        assertTrue("got $gemma", gemma is MigrationOutcome.Unproven)
        val vosk = outcomes.getValue("vosk-models/vosk-small-ru/") as MigrationOutcome.Unproven
        assertTrue(vosk.record.reason, "archive is gone" in vosk.record.reason)

        // A fresh instance sees exactly this from disk: two installed, two legacy/unverified.
        val fresh = installation()
        assertEquals(setOf("multilingual-e5-small-iq4xs@legacy", "whisper-base@legacy"), fresh.installed.all().map { it.variantId.id }.toSet())
        assertTrue(fresh.installed.all().all { fresh.installed.verifyHashes(it) == InstallHealth.Intact })
        assertEquals(setOf("gemma-3-4b-it-q4@legacy", "vosk-small-ru@legacy"), fresh.migrator.records().map { it.variantId.id }.toSet())
        assertTrue(fresh.migrator.records().all { it.status == LegacyRecord.STATUS })

        // A second pass changes nothing and asks the network nothing for what is already in.
        hub.requests.clear()
        val again = scan.installations.first { it.origin.startsWith("whisper/") }
        assertTrue(fresh.migrator.migrate("local-models-legacy", "legacy-mapping-1", again) is MigrationOutcome.AlreadyInStore)
        assertTrue(hub.requests.isEmpty())
    }

    /** The primitive adoption relies on, on its own: may this app hard-link inside its own data directory? */
    @Test
    fun a_hard_link_inside_app_storage() {
        val original = legacy("probe/original.bin", ByteArray(10))
        val link = File(base, "probe/link.bin")
        try {
            java.nio.file.Files.createLink(link.toPath(), original.toPath())
        } catch (e: Exception) {
            throw AssertionError("hard link refused on API ${android.os.Build.VERSION.SDK_INT}: ${e.javaClass.name}: ${e.message}", e)
        }
        assertEquals(inode(original), inode(link))
    }

    @Test
    fun the_legacy_code_replacing_its_file_does_not_change_the_adopted_copy() {
        hub.publish("ggerganov/whisper.cpp", "main", "c".repeat(40), "ggml-base.bin" to whisperBytes)
        val whisper = legacy("whisper/whisper-base.bin", whisperBytes)
        val installation = installation()
        val imported = installation.migrator.migrate(
            "local-models-legacy", "legacy-mapping-1", LegacyInstallationScanner(base, models).scan().installations.single(),
        ).imported()

        // Legacy re-download: write a .part, rename over the old path (ModelDownloader's way).
        val part = File(whisper.path + ".part").apply { writeBytes(ByteArray(100) { 9 }) }
        assertTrue(part.renameTo(whisper))

        val adopted = installation.installed.pathOf(imported.manifest, imported.manifest.artifacts.single())
        assertArrayEquals(whisperBytes, adopted.readBytes())
        assertEquals(InstallHealth.Intact, installation.installed.verifyHashes(imported.manifest))
    }
}
