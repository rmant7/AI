package ai.localstudio.app.modelinstall

import ai.localstudio.app.models.DownloadState
import ai.localstudio.app.models.LocalModelSeed
import ai.localstudio.app.models.LocalModels
import ai.localstudio.app.models.ModelDownloads
import ai.localstudio.app.models.ModelStore
import ai.localstudio.model.ArtifactRoles
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * 3c.1 on a device: chat/translation GGUF models installed through the new
 * chain by [ModelDownloads], [LocalHub] answering as Hugging Face. Every
 * reader -- the local registry, the runtime, the Models screen -- goes
 * through [ModelStore.fileFor]/[ModelStore.mmprojFileFor]/[ModelStore.isInstalled],
 * so these pin what they all see.
 */
@RunWith(AndroidJUnit4::class)
class GgufStoreSwitchDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val base = File(context.cacheDir, "gguf-switch-${System.nanoTime()}")
    private val storeRoot = File(base, ModelInstallation.ROOT_DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val weights = ByteArray(1_200_000) { (it * 11 % 239).toByte() }
    private val projector = ByteArray(300_000) { (it * 5 % 233).toByte() }

    private val seed: LocalModelSeed = LocalModels.custom("test/vision-model-GGUF").copy(mmprojFileName = "mmproj-F16.gguf")

    private fun installation() = ModelInstallation(
        context,
        root = storeRoot,
        hub = HuggingFaceApiClient(apiBase = hub.base),
        transport = HttpRangeTransport(rewrite = hub.rewrite),
        attemptsPerSource = 1,
        retryDelayMs = 0,
    )

    private fun store(installation: ModelInstallation? = installation()) = ModelStore(context, installation, baseDir = base)

    private fun File.isUnder(dir: File) = canonicalPath.startsWith(dir.canonicalPath + File.separator)

    private fun install(store: ModelStore): DownloadState {
        val downloads = ModelDownloads(store, scope = scope)
        downloads.start(seed)
        return runBlocking {
            withTimeout(60_000) {
                downloads.state.first { val s = it[seed.id]; s is DownloadState.Installed || s is DownloadState.Failed }
            }.getValue(seed.id)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun a_download_installs_weights_and_projector_into_the_store() {
        hub.publish("test/vision-model-GGUF", "main", "a".repeat(40), "model-Q4_K_M.gguf" to weights, "mmproj-F16.gguf" to projector)
        val store = store()

        assertEquals(DownloadState.Installed, install(store))
        assertTrue(store.isInstalled(seed))
        assertTrue(store.isInNewStore(seed))
        assertTrue(store.fileFor(seed).isUnder(storeRoot))
        assertTrue(store.mmprojFileFor(seed).isUnder(storeRoot))
        assertTrue(store.hasMmproj(seed))
        assertArrayEquals(weights, store.fileFor(seed).readBytes())
        assertArrayEquals(projector, store.mmprojFileFor(seed).readBytes())
        assertEquals((weights.size + projector.size).toLong(), store.installedSize(seed))
        assertFalse("no legacy copy written", store.legacyFileFor(seed).exists())
        assertEquals(0L, store.partialSize(seed))

        // A fresh process sees the same from disk.
        assertTrue(store().isInNewStore(seed))
    }

    @Test
    fun a_missing_projector_leaves_a_working_text_only_model() {
        hub.publish("test/vision-model-GGUF", "main", "b".repeat(40), "model-Q4_K_M.gguf" to weights)
        val store = store()

        assertEquals(DownloadState.Installed, install(store))
        assertTrue(store.isInNewStore(seed))
        assertFalse(store.hasMmproj(seed))
        val manifest = store.installation!!.installed.manifest(store.variantId(seed))!!
        assertEquals(listOf(ArtifactRoles.PROJECTOR), manifest.skippedOptional.map { it.role })
        // A backfilled projector lands beside the legacy files and is found there.
        store.legacyMmprojFileFor(seed).writeBytes(projector)
        assertTrue(store.hasMmproj(seed))
        assertEquals(store.legacyMmprojFileFor(seed), store.mmprojFileFor(seed))
    }

    @Test
    fun a_legacy_model_keeps_working_and_delete_removes_both() {
        hub.publish("test/vision-model-GGUF", "main", "c".repeat(40), "model-Q4_K_M.gguf" to weights, "mmproj-F16.gguf" to projector)
        val store = store()
        val legacy = store.legacyFileFor(seed)
        RandomAccessFile(legacy, "rw").use { it.setLength(60L * 1024 * 1024) }
        assertTrue(store.isInstalled(seed))
        assertFalse(store.isInNewStore(seed))
        assertEquals("no store copy: the legacy file is what loads", legacy, store.fileFor(seed))

        // Both present (a store install over a leftover legacy file): the store copy wins.
        val legacyBytes = legacy.length()
        legacy.renameTo(File(base, "aside"))
        assertEquals(DownloadState.Installed, install(store))
        File(base, "aside").renameTo(legacy)
        assertEquals(legacyBytes, legacy.length())
        assertTrue(store.fileFor(seed).isUnder(storeRoot))

        store.delete(seed)
        assertFalse(store.isInstalled(seed))
        assertFalse(legacy.exists())
        assertTrue(store.installation!!.installed.all().isEmpty())
    }

    @Test
    fun an_interrupted_store_download_counts_as_paused_bytes() {
        val store = store()
        val staging = store.installation!!.layout.stagingDir(store.variantId(seed)).apply { mkdirs() }
        File(staging, "model.gguf.part").writeBytes(ByteArray(4096))
        File(staging, "model.gguf.part.json").writeText("{}")
        assertEquals("what the Models screen shows as resumable", 4096L, store.partialSize(seed))
        assertFalse(store.isInstalled(seed))

        store.delete(seed)
        assertEquals(0L, store.partialSize(seed))
    }

    @Test
    fun without_the_new_chain_the_store_is_the_legacy_store() {
        val store = store(installation = null)
        assertEquals(File(File(base, "models"), "${seed.id}.gguf"), store.fileFor(seed))
        assertFalse(store.isInNewStore(seed))
    }
}
