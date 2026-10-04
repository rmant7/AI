package ai.localstudio.app.llama

import ai.localstudio.app.modelinstall.HttpRangeTransport
import ai.localstudio.app.modelinstall.HuggingFaceApiClient
import ai.localstudio.app.modelinstall.LocalHub
import ai.localstudio.app.modelinstall.ModelInstallation
import ai.localstudio.model.install.AdoptionOutcome
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * 3b.3 on a device: semantic memory's E5 Base switched onto the new install
 * chain, [LocalHub] answering as Hugging Face. Every screen and the memory
 * loader read [ExperimentalEmbeddingStore.fileFor]/[ExperimentalEmbeddingStore.isInstalled],
 * so these pin what they all see: the store's copy once there is one, the
 * legacy file whenever the new chain could not take over.
 */
@RunWith(AndroidJUnit4::class)
class EmbeddingStoreSwitchDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val base = File(context.cacheDir, "embedding-switch-${System.nanoTime()}")
    private val storeRoot = File(base, ModelInstallation.ROOT_DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val spec = ExperimentalEmbeddingModels.E5_BASE

    // Above the store's 1 MiB plausibility floor.
    private val e5Bytes = ByteArray(1_500_000) { (it * 7 % 241).toByte() }

    private fun installation() = ModelInstallation(
        context,
        root = storeRoot,
        hub = HuggingFaceApiClient(apiBase = hub.base),
        transport = HttpRangeTransport(rewrite = hub.rewrite),
        attemptsPerSource = 1,
        retryDelayMs = 0,
    )

    private fun store(installation: ModelInstallation? = installation()) =
        ExperimentalEmbeddingStore(context, installation, baseDir = base)

    private fun publishE5(bytes: ByteArray = e5Bytes) =
        hub.publish(spec.repoId, "main", "e".repeat(40), "multilingual-e5-base-q4_k_m.gguf" to bytes)

    private fun legacyE5(store: ExperimentalEmbeddingStore, bytes: ByteArray = e5Bytes) =
        store.legacyFileFor(spec).apply { writeBytes(bytes) }

    private fun File.isUnder(dir: File) = canonicalPath.startsWith(dir.canonicalPath + File.separator)

    @After
    fun tearDown() {
        scope.cancel()
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun a_proven_legacy_file_is_adopted_and_every_reader_switches_to_the_store_copy() {
        publishE5()
        val store = store()
        val legacy = legacyE5(store)
        assertEquals("before adoption: the legacy file", legacy, store.fileFor(spec))

        val outcome = store.adoptLegacy(spec)
        assertTrue("got $outcome", outcome is AdoptionOutcome.Adopted)
        assertTrue(store.isInNewStore(spec))
        assertTrue(store.isInstalled(spec))
        assertTrue("${store.fileFor(spec)} is not in the model store", store.fileFor(spec).isUnder(storeRoot))
        assertArrayEquals(e5Bytes, store.fileFor(spec).readBytes())
        assertFalse("moved, not copied: one owner", legacy.exists())
        assertEquals(e5Bytes.size.toLong(), store.installedSize(spec))

        // A fresh process sees the same from disk, and adopting again asks nobody anything.
        hub.requests.clear()
        val fresh = store()
        assertTrue(fresh.isInNewStore(spec))
        assertNull(fresh.adoptLegacy(spec))
        assertTrue(hub.requests.isEmpty())
    }

    @Test
    fun an_unproven_legacy_file_stays_where_it_is_and_keeps_being_used() {
        publishE5(ByteArray(e5Bytes.size) { 3 })
        val store = store()
        val legacy = legacyE5(store)

        val outcome = store.adoptLegacy(spec)
        assertTrue("got $outcome", outcome is AdoptionOutcome.NotProven)
        assertFalse(store.isInNewStore(spec))
        assertEquals(legacy, store.fileFor(spec))
        assertTrue(store.isInstalled(spec))
        assertArrayEquals(e5Bytes, legacy.readBytes())

        // Settled for this process: the next reload neither re-hashes nor asks the hub.
        hub.requests.clear()
        assertNull(store.adoptLegacy(spec))
        assertTrue(hub.requests.isEmpty())
    }

    @Test
    fun without_the_new_chain_the_store_is_the_legacy_store() {
        val store = store(installation = null)
        val legacy = legacyE5(store)
        assertNull(store.adoptLegacy(spec))
        assertEquals(legacy, store.fileFor(spec))
        assertTrue(store.isInstalled(spec))
    }

    @Test
    fun a_download_installs_through_the_new_chain_and_delete_removes_it() {
        publishE5()
        val store = store()
        val downloads = ExperimentalEmbeddingDownloads(store, scope = scope)
        assertFalse(store.isInstalled(spec))

        downloads.start(spec)
        val state = runBlocking {
            withTimeout(60_000) {
                downloads.state.first { val s = it[spec.id]; s is ExperimentalDownloadState.Installed || s is ExperimentalDownloadState.Failed }
            }.getValue(spec.id)
        }
        assertEquals(ExperimentalDownloadState.Installed, state)
        assertTrue(store.isInNewStore(spec))
        assertTrue(store.fileFor(spec).isUnder(storeRoot))
        assertArrayEquals(e5Bytes, store.fileFor(spec).readBytes())
        assertFalse("no legacy copy written", store.legacyFileFor(spec).exists())
        assertFalse(store.partFor(spec).exists())
        assertTrue(hub.fileRequests().isNotEmpty())

        downloads.delete(spec)
        assertFalse(store.isInstalled(spec))
        assertFalse(store.isInNewStore(spec))
        assertTrue(store.installation!!.installed.all().isEmpty())
        assertEquals(ExperimentalDownloadState.Idle, downloads.stateOf(spec))
    }

    @Test
    fun files_of_removed_models_are_deleted_and_e5_base_is_kept() {
        val store = store(installation = null)
        val e5Base = legacyE5(store)
        val e5SmallFile = File(store.directory(), "multilingual-e5-small-iq4xs.gguf").apply { writeBytes(ByteArray(10)) }
        val e5SmallPart = File(store.directory(), "multilingual-e5-small-iq4xs.gguf.part").apply { writeBytes(ByteArray(10)) }
        val e5BasePart = store.partFor(spec).apply { writeBytes(ByteArray(10)) }

        store.deleteRemovedModels()
        assertFalse(e5SmallFile.exists())
        assertFalse(e5SmallPart.exists())
        assertTrue(e5Base.isFile)
        assertTrue("a resumable part of a current model is kept", e5BasePart.isFile)
    }
}
