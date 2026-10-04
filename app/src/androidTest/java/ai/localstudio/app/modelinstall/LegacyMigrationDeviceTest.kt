package ai.localstudio.app.modelinstall

import ai.localstudio.model.CatalogLoader
import ai.localstudio.model.CatalogTrust
import ai.localstudio.model.ModelDefinition
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.IntegrityBasis
import ai.localstudio.model.install.LegacyRecord
import ai.localstudio.model.install.AdoptionOutcome
import ai.localstudio.model.install.MigrationOutcome
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 3b.2 on a device: a legacy file layout (the four legacy stores) in a
 * scratch directory, the generated catalogue, [LocalHub] answering as
 * Hugging Face — scanned, proven or rejected without touching a file, then
 * adopted by rename on the device's real filesystem. (Hard links were the
 * first design; Android refuses them in app storage — AccessDeniedException
 * on API 30, build #434.)
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

    private fun AdoptionOutcome.adopted(): AdoptionOutcome.Adopted =
        this as? AdoptionOutcome.Adopted ?: throw AssertionError("expected Adopted, got $this")

    @After
    fun tearDown() {
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun legacy_stores_are_scanned_and_proven_without_touching_a_file_then_adopted_by_rename() {
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

        // Step 1 — prove. Nothing on disk changes.
        val installation = installation()
        val whisperInode = inode(whisper)
        val outcomes = scan.installations.associate { it.origin to installation.migrator.migrate(it) }
        val whisperProof = (outcomes.getValue("whisper/whisper-base.bin") as MigrationOutcome.Proven).record.proofs.single()
        assertEquals("c".repeat(40), whisperProof.source.commit)
        assertEquals(LocalHub.sha256(whisperBytes), whisperProof.sha256)
        assertTrue(outcomes.getValue("experimental_embeddings/multilingual-e5-small-iq4xs.gguf") is MigrationOutcome.Proven)
        assertTrue(outcomes.getValue("models/gemma-3-4b-it-q4.gguf") is MigrationOutcome.Unproven)
        val vosk = outcomes.getValue("vosk-models/vosk-small-ru/") as MigrationOutcome.Unproven
        assertTrue(vosk.record.reason!!, "archive is gone" in vosk.record.reason!!)
        assertArrayEquals(whisperBytes, whisper.readBytes())
        assertArrayEquals(e5Bytes, e5.readBytes())
        assertTrue("proof is not an install", installation.installed.all().isEmpty())

        // A fresh instance sees the same from disk: two proven, two legacy/unverified.
        val fresh = installation()
        assertEquals(
            mapOf(
                "gemma-3-4b-it-q4@legacy" to LegacyRecord.UNVERIFIED,
                "multilingual-e5-small-iq4xs@legacy" to LegacyRecord.PROVEN,
                "vosk-small-ru@legacy" to LegacyRecord.UNVERIFIED,
                "whisper-base@legacy" to LegacyRecord.PROVEN,
            ),
            fresh.migrator.records().associate { it.variantId.id to it.status },
        )

        // Step 2 — the consumer switches: adopt by rename.
        val whisperInstall = scan.installations.first { it.origin.startsWith("whisper/") }
        val manifest = fresh.migrator.adopt("local-models-legacy", "legacy-mapping-1", whisperInstall).adopted().manifest
        val adopted = fresh.installed.pathOf(manifest, manifest.artifacts.single())
        assertEquals("a rename: the same inode, no copy", whisperInode, inode(adopted))
        assertFalse("the legacy path is gone: one owner", whisper.exists())
        assertEquals(IntegrityBasis.UPSTREAM_SHA256, manifest.artifacts.single().integrity)
        assertEquals(InstallHealth.Intact, fresh.installed.verifyHashes(manifest))
        assertNull(fresh.migrator.record(manifest.variantId))

        hub.requests.clear()
        assertTrue(installation().migrator.adopt("local-models-legacy", "legacy-mapping-1", whisperInstall) is AdoptionOutcome.AlreadyInStore)
        assertTrue(hub.requests.isEmpty())
        assertArrayEquals("not adopted yet: e5 still where the legacy code expects it", e5Bytes, e5.readBytes())
    }

    @Test
    fun a_file_changed_after_its_proof_is_re_proven_and_not_moved() {
        hub.publish("ggerganov/whisper.cpp", "main", "c".repeat(40), "ggml-base.bin" to whisperBytes)
        val whisper = legacy("whisper/whisper-base.bin", whisperBytes)
        val found = LegacyInstallationScanner(base, models).scan().installations.single()
        val installation = installation()
        assertTrue(installation.migrator.migrate(found) is MigrationOutcome.Proven)

        // The legacy code re-downloads before the switch: same name, other bytes.
        val part = File(whisper.path + ".part").apply { writeBytes(ByteArray(whisperBytes.size) { 9 }) }
        assertTrue(part.renameTo(whisper))

        val outcome = installation.migrator.adopt("local-models-legacy", "legacy-mapping-1", found)
        assertTrue("got $outcome", outcome is AdoptionOutcome.NotProven)
        assertTrue("nothing moved", whisper.isFile)
        assertTrue(installation.installed.all().isEmpty())
    }
}
