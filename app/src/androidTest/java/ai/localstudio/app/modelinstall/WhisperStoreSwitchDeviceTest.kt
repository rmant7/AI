package ai.localstudio.app.modelinstall

import ai.localstudio.app.whisper.WhisperDownloadState
import ai.localstudio.app.whisper.WhisperDownloads
import ai.localstudio.app.whisper.WhisperModelSeed
import ai.localstudio.app.whisper.WhisperStore
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
 * 3c.2 on a device: Whisper models installed through the new chain by
 * [WhisperDownloads], [LocalHub] answering as Hugging Face. Every engine
 * reads [WhisperStore.modelFile]/[WhisperStore.isInstalled].
 */
@RunWith(AndroidJUnit4::class)
class WhisperStoreSwitchDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val base = File(context.cacheDir, "whisper-switch-${System.nanoTime()}")
    private val storeRoot = File(base, ModelInstallation.ROOT_DIR)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bytes = ByteArray(800_000) { (it * 13 % 251).toByte() }
    private val seed = WhisperModelSeed(
        id = "whisper-test",
        title = "Whisper Test",
        modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-test.bin",
        approxSizeBytes = bytes.size.toLong(),
    )

    private fun installation() = ModelInstallation(
        context,
        root = storeRoot,
        hub = HuggingFaceApiClient(apiBase = hub.base),
        transport = HttpRangeTransport(rewrite = hub.rewrite),
        attemptsPerSource = 1,
        retryDelayMs = 0,
    )

    private fun store(installation: ModelInstallation? = installation()) = WhisperStore(context, installation, baseDir = base)

    private fun File.isUnder(dir: File) = canonicalPath.startsWith(dir.canonicalPath + File.separator)

    @After
    fun tearDown() {
        scope.cancel()
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun a_download_installs_into_the_store_and_delete_removes_it() {
        hub.publish("ggerganov/whisper.cpp", "main", "e".repeat(40), "ggml-test.bin" to bytes)
        val store = store()
        val downloads = WhisperDownloads(store, scope = scope)

        downloads.start(seed)
        val state = runBlocking {
            withTimeout(60_000) {
                downloads.state.first { val s = it[seed.id]; s is WhisperDownloadState.Installed || s is WhisperDownloadState.Failed }
            }.getValue(seed.id)
        }
        assertEquals(WhisperDownloadState.Installed, state)
        assertTrue(store.isInNewStore(seed))
        assertTrue(store.isInstalled(seed))
        assertTrue(store.modelFile(seed).isUnder(storeRoot))
        assertArrayEquals(bytes, store.modelFile(seed).readBytes())
        assertFalse("no legacy copy written", store.legacyModelFile(seed).exists())
        assertEquals(seed, store.installedSeed(candidates = listOf(seed)))

        downloads.delete(seed)
        assertFalse(store.isInstalled(seed))
        assertTrue(store.installation!!.installed.all().isEmpty())
    }

    @Test
    fun a_legacy_model_keeps_working() {
        val store = store()
        val legacy = store.legacyModelFile(seed)
        RandomAccessFile(legacy, "rw").use { it.setLength(20L * 1024 * 1024) }
        assertTrue(store.isInstalled(seed))
        assertFalse(store.isInNewStore(seed))
        assertEquals(legacy, store.modelFile(seed))

        store.delete(seed)
        assertFalse(legacy.exists())
    }

    @Test
    fun without_the_new_chain_the_store_is_the_legacy_store() {
        val store = store(installation = null)
        assertEquals(File(File(base, "whisper"), "whisper-test.bin"), store.modelFile(seed))
    }
}
