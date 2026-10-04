package ai.localstudio.app.modelinstall

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
import ai.localstudio.model.ResourceRequirements
import ai.localstudio.model.RuntimeBinding
import ai.localstudio.model.Runtimes
import ai.localstudio.model.VariantId
import ai.localstudio.model.install.Admission
import ai.localstudio.model.install.InstallHealth
import ai.localstudio.model.install.InstallResult
import ai.localstudio.model.install.IntegrityBasis
import ai.localstudio.model.install.ResourceAdmission
import ai.localstudio.model.install.SourceException
import ai.localstudio.model.install.TransferCancelledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * The Phase 3b install chain on a real device/emulator: the real
 * HttpURLConnection stack, a real filesystem, real StatFs and
 * ActivityManager — against [LocalHub], a local stand-in for Hugging Face
 * whose faults are scripted.
 *
 * "Process killed" is simulated by abandoning every object of one
 * [ModelInstallation] mid-operation and building a brand-new one on the same
 * directory: nothing but the files on disk carries over, which is exactly
 * the recovery criterion (state from disk and manifest, never from memory).
 */
@RunWith(AndroidJUnit4::class)
class ModelInstallDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val hub = LocalHub()
    private val root = File(context.cacheDir, "model-install-test-${System.nanoTime()}")
    private var token: String? = "hf_test_token"

    private val repo = "org/emb-GGUF"
    private val commitA = "a".repeat(40)
    private val commitB = "b".repeat(40)
    private val weights = ByteArray(3 * 1024 * 1024) { (it * 31 % 251).toByte() }
    private val weightsB = ByteArray(3 * 1024 * 1024) { (it * 17 % 241).toByte() }
    private val projector = ByteArray(512 * 1024) { (it * 7 % 239).toByte() }
    private val weightsFile = "emb-Q4_K_M.gguf"
    private val variantId = VariantId("emb@legacy")

    private fun installation(attempts: Int = 3) = ModelInstallation(
        context,
        root = root,
        token = { token },
        hub = HuggingFaceApiClient(apiBase = hub.base, token = { token }),
        transport = HttpRangeTransport(token = { token }, rewrite = hub.rewrite),
        attemptsPerSource = attempts,
        retryDelayMs = 10,
    )

    private fun model(measuredPeakRam: Long? = null) = ModelDefinition(
        id = ModelId("emb"),
        family = ModelFamilyId("emb"),
        displayName = "Emb",
        status = CatalogStatus.UNVERIFIED,
        capabilities = mapOf(
            Capabilities.TEXT_GENERATION to GenericFacet(),
            Capabilities.VISION to GenericFacet(setOf(ArtifactRoles.PROJECTOR)),
        ),
        variants = listOf(
            ModelVariant(
                variantId,
                artifacts = listOf(
                    ArtifactSpec(
                        ArtifactRoles.WEIGHTS, "model.gguf", 1, null,
                        ArtifactSource.HuggingFaceSelection(listOf("gated/emb-GGUF", repo), "main", FileSelector.ByQuantization(listOf("Q4_K_M"), ".gguf")),
                    ),
                    ArtifactSpec(
                        ArtifactRoles.PROJECTOR, "projector.gguf", 1, null, optional = true,
                        source = ArtifactSource.HuggingFaceSelection(listOf(repo), "main", FileSelector.ExactName("mmproj-F16.gguf")),
                    ),
                ),
                bindings = listOf(RuntimeBinding(Runtimes.LLAMA_CPP, setOf(ArtifactRoles.WEIGHTS), setOf(ArtifactRoles.PROJECTOR))),
                resources = ResourceRequirements(measuredPeakRamBytes = measuredPeakRam),
            ),
        ),
    )

    private fun publishA() = hub.publish(repo, "main", commitA, weightsFile to weights, "mmproj-F16.gguf" to projector, "README.md" to ByteArray(10))

    private fun ModelInstallation.install(model: ModelDefinition = model(), cancelAfterBytes: Long? = null, force: Boolean = false): InstallResult {
        var seen = 0L
        return installer.install(
            "test", "1", model, model.variants.single(), ::freeBytes,
            cancel = { cancelAfterBytes != null && seen >= cancelAfterBytes },
            force = force,
        ) { seen = it.transfer.bytesDone }
    }

    private fun installedDir() = File(root, variantId.id)

    private fun stagingPart() = File(root, ".staging/${variantId.id}/model.gguf.part")

    @After
    fun tearDown() {
        hub.close()
        root.deleteRecursively()
    }

    // --- adapters ---

    @Test
    fun hubClient_reads_commit_and_listing_with_lfs_sizes_and_hashes() {
        publishA()
        val client = HuggingFaceApiClient(apiBase = hub.base)
        assertEquals(commitA, client.resolveCommit(repo, "main"))
        val files = client.listFiles(repo, commitA)
        assertEquals(setOf(weightsFile, "mmproj-F16.gguf", "README.md"), files.map { it.path }.toSet())
        val w = files.single { it.path == weightsFile }
        assertEquals("lfs.size, not the 134-byte pointer", weights.size.toLong(), w.sizeBytes)
        assertEquals(LocalHub.sha256(weights), w.lfsSha256)
        try {
            client.resolveCommit("nobody/none", "main")
            fail("expected NOT_FOUND")
        } catch (e: SourceException) {
            assertEquals(SourceException.Kind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun token_goes_to_huggingface_only_never_through_the_cdn_redirect() {
        publishA()
        assertTrue(installation().install() is InstallResult.Installed)
        val resolveHops = hub.requests.filter { it.path.startsWith("/hf/") }
        val cdnHops = hub.fileRequests()
        assertTrue(resolveHops.isNotEmpty() && resolveHops.all { it.authorization == "Bearer hf_test_token" })
        assertTrue("the CDN must never see the token", cdnHops.isNotEmpty() && cdnHops.all { it.authorization == null })
    }

    // --- the happy path, then every fault ---

    @Test
    fun install_on_device_is_verified_recorded_and_discoverable() {
        publishA()
        val result = installation().install() as InstallResult.Installed
        assertArrayEquals(weights, File(installedDir(), "model.gguf").readBytes())
        assertArrayEquals(projector, File(installedDir(), "projector.gguf").readBytes())
        val weightsRecord = result.manifest.artifacts.first()
        assertEquals(commitA, weightsRecord.source.commit)
        assertEquals(IntegrityBasis.UPSTREAM_SHA256, weightsRecord.integrity)
        assertTrue("the gated first repository was skipped and logged", result.log.any { "gated/emb-GGUF" in it.source })

        // Discovery by a fresh instance, from disk only.
        val fresh = installation()
        val found = fresh.installed.all().single()
        assertEquals(result.manifest, found)
        assertEquals(InstallHealth.Intact, fresh.installed.verifyHashes(found))
    }

    @Test
    fun a_dropped_connection_resumes_with_a_range_request() {
        publishA()
        hub.cutAfter[weightsFile] = ArrayDeque(listOf(700_000, 900_000))
        assertTrue(installation().install() is InstallResult.Installed)
        val ranges = hub.fileRequests().filter { it.path.endsWith(weightsFile) }.map { it.range }
        assertEquals(listOf(null, "bytes=700000-", "bytes=1600000-"), ranges)
        assertArrayEquals(weights, File(installedDir(), "model.gguf").readBytes())
    }

    @Test
    fun killed_during_download_a_new_instance_resumes_from_the_part_on_disk() {
        publishA()
        try {
            installation().install(cancelAfterBytes = 1_000_000)
            fail("expected the run to be cancelled")
        } catch (e: TransferCancelledException) {
            // the "process" dies here
        }
        val kept = stagingPart().length()
        assertTrue("part kept on disk: $kept", kept >= 1_000_000)
        hub.requests.clear()

        assertTrue(installation().install() is InstallResult.Installed)
        assertEquals("bytes=$kept-", hub.fileRequests().first { it.path.endsWith(weightsFile) }.range)
        assertArrayEquals(weights, File(installedDir(), "model.gguf").readBytes())
    }

    @Test
    fun failed_on_network_errors_a_later_run_resumes_instead_of_restarting() {
        publishA()
        hub.cutAfter[weightsFile] = ArrayDeque(listOf(600_000))
        assertTrue(installation(attempts = 1).install() is InstallResult.Failed)
        assertFalse(installedDir().exists())
        assertEquals(600_000L, stagingPart().length())
        hub.requests.clear()
        assertTrue(installation().install() is InstallResult.Installed)
        assertEquals("bytes=600000-", hub.fileRequests().first { it.path.endsWith(weightsFile) }.range)
    }

    @Test
    fun killed_after_everything_was_staged_a_new_instance_finishes_without_downloading() {
        publishA()
        // Every byte downloaded and verified into staging, then the process dies
        // before the manifest is written and the directory moved into place.
        val staging = File(root, ".staging/${variantId.id}").apply { mkdirs() }
        File(staging, "model.gguf").writeBytes(weights)
        File(staging, "projector.gguf").writeBytes(projector)
        hub.requests.clear()

        assertTrue(installation().install() is InstallResult.Installed)
        assertTrue("files proven by sha256 are reused: ${hub.fileRequests()}", hub.fileRequests().isEmpty())
        assertFalse(staging.exists())
    }

    @Test
    fun a_server_ignoring_range_restarts_the_file_instead_of_splicing() {
        publishA()
        hub.cutAfter[weightsFile] = ArrayDeque(listOf(800_000))
        hub.ignoreRange += weightsFile
        assertTrue(installation().install() is InstallResult.Installed)
        assertArrayEquals(weights, File(installedDir(), "model.gguf").readBytes())
        assertEquals("bytes=800000-", hub.fileRequests().filter { it.path.endsWith(weightsFile) }[1].range)
    }

    @Test
    fun a_moved_branch_discards_the_old_commits_part_and_installs_the_new_commit() {
        publishA()
        try {
            installation().install(cancelAfterBytes = 1_000_000)
            fail("expected cancellation")
        } catch (e: TransferCancelledException) {
        }
        hub.publish(repo, "main", commitB, weightsFile to weightsB, "mmproj-F16.gguf" to projector)
        hub.requests.clear()

        val result = installation().install() as InstallResult.Installed
        assertEquals(commitB, result.manifest.artifacts.first().source.commit)
        assertNull("no resume across commits", hub.fileRequests().first { it.path.endsWith(weightsFile) }.range)
        assertArrayEquals(weightsB, File(installedDir(), "model.gguf").readBytes())
    }

    @Test
    fun bytes_not_matching_the_upstream_hash_are_rejected_and_not_kept() {
        publishA()
        hub.corrupt += weightsFile
        val failed = installation().install() as InstallResult.Failed
        assertTrue(failed.failures.any { it.message.startsWith("sha256") })
        assertFalse(installedDir().exists())
        assertFalse(stagingPart().exists())
    }

    @Test
    fun not_enough_space_by_real_statfs_stops_before_any_download() {
        val installation = installation()
        val free = installation.freeBytes()
        assertTrue("StatFs reports free space: $free", free > 0)
        hub.publish(repo, "main", commitA, weightsFile to weights, listedSize = free + (1L shl 30))
        val result = installation.install()
        assertTrue("got $result", result is InstallResult.InsufficientStorage)
        val reported = (result as InstallResult.InsufficientStorage).freeBytes
        assertTrue("StatFs free space $reported, sampled $free", kotlin.math.abs(reported - free) < 64L * 1024 * 1024)
        assertTrue(hub.fileRequests().isEmpty())
    }

    @Test
    fun memory_admission_uses_the_real_available_memory() {
        publishA()
        val installation = installation()
        val manifest = (installation.install() as InstallResult.Installed).manifest
        val available = installation.probe.availableMemoryBytes()
        assertTrue("ActivityManager reports memory: $available", available > 0)

        val fits = ResourceAdmission.memory(model().variants.single(), setOf(ArtifactRoles.WEIGHTS), manifest.sizesByRole, available)
        assertEquals(Admission.Admit, fits)
        val tooBig = model(measuredPeakRam = available * 4).variants.single()
        assertTrue(ResourceAdmission.memory(tooBig, setOf(ArtifactRoles.WEIGHTS), manifest.sizesByRole, available) is Admission.Deny)
    }

    @Test
    fun a_corrupted_install_is_detected_and_reinstalled() {
        publishA()
        val installation = installation()
        installation.install()
        val file = File(installedDir(), "model.gguf")
        RandomAccessFile(file, "rw").use { it.seek(12345); it.write(0x55) }
        val manifest = installation.installed.all().single()
        assertEquals("same size: the cheap check can't see it", InstallHealth.Intact, installation.installed.health(manifest))
        assertTrue(installation.installed.verifyHashes(manifest) is InstallHealth.Damaged)

        file.writeBytes(ByteArray(10))
        assertTrue(installation.installed.health(manifest) is InstallHealth.Damaged)
        assertTrue(installation().install() is InstallResult.Installed)
        assertArrayEquals(weights, file.readBytes())
    }

    @Test
    fun a_second_run_after_success_touches_neither_network_nor_files() {
        publishA()
        assertTrue(installation().install() is InstallResult.Installed)
        val before = File(installedDir(), "model.gguf").lastModified()
        hub.requests.clear()
        assertTrue(installation().install() is InstallResult.AlreadyInstalled)
        assertTrue(hub.requests.isEmpty())
        assertEquals(before, File(installedDir(), "model.gguf").lastModified())
        assertNotNull(installation().installed.manifest(variantId))
    }
}
