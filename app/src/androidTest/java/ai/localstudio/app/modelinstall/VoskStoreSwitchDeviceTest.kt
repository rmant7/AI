package ai.localstudio.app.modelinstall

import ai.localstudio.app.models.catalog.LegacyCatalogMapper
import ai.localstudio.app.vosk.VoskModelSeed
import ai.localstudio.app.vosk.VoskModelStore
import ai.localstudio.app.vosk.VoskModels
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.ModelId
import ai.localstudio.model.install.InstallResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 3c.3 on a device: a Vosk archive installed and unpacked by the new chain
 * is what [VoskModelStore.modelDir] hands `Model(path)` -- `am/`, `conf/`
 * directly inside, the archive's top-level folder stripped as the legacy
 * extract does. A test-only seed id, so a real model on the device (in the
 * legacy `vosk-models/` directory) is never touched.
 */
@RunWith(AndroidJUnit4::class)
class VoskStoreSwitchDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val base = File(context.cacheDir, "vosk-switch-${System.nanoTime()}")
    private val storeRoot = File(base, ModelInstallation.ROOT_DIR)

    private val seed = VoskModelSeed("vosk-switch-test", "Vosk test", listOf("https://huggingface.co/test/vosk/resolve/main/vosk-model-test-0.1.zip"), 0)
    private val mdl = ByteArray(200_000) { (it * 3 % 241).toByte() }
    private val conf = "--sample-frequency=16000\n".toByteArray()

    private fun zip(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in listOf("vosk-model-test-0.1/am/final.mdl" to mdl, "vosk-model-test-0.1/conf/model.conf" to conf)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun installation() = ModelInstallation(
        context,
        root = storeRoot,
        hub = HuggingFaceApiClient(apiBase = hub.base),
        transport = HttpRangeTransport(rewrite = hub.rewrite),
        attemptsPerSource = 1,
        retryDelayMs = 0,
    )

    /** The bundled vosk-small-ru mapping (zip, strip 1), re-pointed at this test's seed and archive. */
    private fun definition() = LegacyCatalogMapper.voskModel(VoskModels.SEEDS.first { it.id == "vosk-small-ru" }).let { model ->
        val variant = model.variants.single()
        model.copy(
            id = ModelId(seed.id),
            variants = listOf(
                variant.copy(
                    id = VoskModelStore.variantId(seed),
                    artifacts = variant.artifacts.map {
                        it.copy(sizeBytes = 0, source = ArtifactSource.HuggingFace("test/vosk", "main", "vosk-model-test-0.1.zip"))
                    },
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        VoskModelStore.installation = null
        hub.close()
        base.deleteRecursively()
    }

    @Test
    fun an_installed_archive_is_the_directory_vosk_loads_and_delete_removes_it() {
        hub.publish("test/vosk", "main", "f".repeat(40), "vosk-model-test-0.1.zip" to zip())
        val installation = installation()
        VoskModelStore.installation = installation
        assertFalse(VoskModelStore.isInstalled(context, seed))

        val model = definition()
        val result = installation.installer.install(
            LegacyCatalogMapper.CATALOG_ID, LegacyCatalogMapper.CATALOG_VERSION, model, model.variants.single(), installation::freeBytes,
        )
        assertTrue("got $result", result is InstallResult.Installed)

        assertTrue(VoskModelStore.isInNewStore(seed))
        assertTrue(VoskModelStore.isInstalled(context, seed))
        val dir = VoskModelStore.modelDir(context, seed)
        assertTrue(dir.canonicalPath.startsWith(storeRoot.canonicalPath + File.separator))
        assertArrayEquals(mdl, File(dir, "am/final.mdl").readBytes())
        assertArrayEquals(conf, File(dir, "conf/model.conf").readBytes())

        VoskModelStore.delete(context, seed)
        assertFalse(VoskModelStore.isInstalled(context, seed))
        assertTrue(installation.installed.all().isEmpty())
    }

    @Test
    fun without_the_new_chain_the_legacy_directory_is_used() {
        VoskModelStore.installation = null
        assertEquals(File(VoskModelStore.directory(context), seed.id), VoskModelStore.modelDir(context, seed))
        assertFalse(VoskModelStore.isInstalled(context, seed))
    }
}
