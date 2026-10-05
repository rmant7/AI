package ai.localstudio.model.install

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationStoreTest {

    private val gemma = ArtifactId("unsloth/gemma-4-E4B-it-GGUF", "c".repeat(40), "gemma-4-E4B-it-Q4_K_M.gguf", "mmproj-F16.gguf")
    private val other = ArtifactId("acme/a-GGUF", "a".repeat(40), "a.gguf")

    private fun check(artifact: ArtifactId?, at: Long, vision: CheckStatus = CheckStatus.PASS) = DeviceVerification(
        deviceProfile = "Pixel 10 Pro",
        runtimeId = "llama_cpp",
        loaded = true,
        inferenceOk = true,
        verifiedAtEpochMs = at,
        checkVersion = DeviceVerification.CURRENT_CHECK,
        checks = mapOf(
            VerifiedCapability.VISION to CapabilityCheck(
                vision,
                steps = listOf(ProbeStep("image: the digit 7", passed = true, answer = "7", firstTokenMs = 900, tokensPerSecond = 12.5)),
                failureKind = if (vision == CheckStatus.FAIL) FailureKind.MODEL_ANSWER else null,
            ),
        ),
        artifact = artifact,
        device = "Google Pixel 10 Pro",
        runtimeVersion = "llama.cpp b10448 / jni 3",
    )

    private fun store() = VerificationStore(Files.createTempDirectory("checks").resolve("checks.json").toFile())

    @Test
    fun keeps_the_latest_check_per_artifact_across_instances() {
        val file = Files.createTempDirectory("checks").resolve("checks.json").toFile()
        VerificationStore(file).record(check(gemma, 1_000, CheckStatus.FAIL))
        VerificationStore(file).record(check(gemma, 2_000))
        VerificationStore(file).record(check(other, 1_500))
        val latest = VerificationStore(file).latest(gemma)!!
        assertEquals(2_000, latest.verifiedAtEpochMs)
        assertEquals(CheckStatus.PASS, latest.recorded(VerifiedCapability.VISION))
        assertEquals(12.5, latest.checks.getValue(VerifiedCapability.VISION).steps.single().tokensPerSecond)
        assertEquals(1_500, VerificationStore(file).latest(other)!!.verifiedAtEpochMs)
    }

    @Test
    fun an_older_check_never_replaces_a_newer_one() {
        val store = store()
        store.record(check(gemma, 2_000))
        store.record(check(gemma, 1_000, CheckStatus.FAIL))
        assertEquals(2_000, store.latest(gemma)!!.verifiedAtEpochMs)
    }

    @Test
    fun a_record_with_no_artifact_is_not_kept() {
        val store = store()
        assertFalse(store.record(check(null, 1_000)))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun forget_removes_one_artifact_only() {
        val store = store()
        store.record(check(gemma, 1_000))
        store.record(check(other, 1_000))
        assertTrue(store.forget(gemma))
        assertNull(store.latest(gemma))
        assertEquals(1, store.all().size)
    }

    @Test
    fun an_unpinned_id_changes_with_the_files_and_is_not_a_repository() {
        val a = ArtifactId.unpinned("gemma-4-e4b-it-q4", 4_980_000_000, 990_000_000)
        assertFalse(a.isPinned)
        assertTrue(gemma.isPinned)
        assertEquals("projector.gguf", a.projectorFile)
        assertNotEquals(a, ArtifactId.unpinned("gemma-4-e4b-it-q4", 4_980_000_001, 990_000_000))
        assertNotEquals(a, ArtifactId.unpinned("gemma-4-e4b-it-q4", 4_980_000_000, null))
        assertNull(ArtifactId.unpinned("x", 1, null).projectorFile)
    }
}
