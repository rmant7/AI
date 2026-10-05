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
    fun an_unpinned_id_is_the_files_contents_and_is_not_a_repository() {
        val a = ArtifactId.unpinned("a".repeat(64), "b".repeat(64))
        assertFalse(a.isPinned)
        assertTrue(gemma.isPinned)
        assertEquals("projector.gguf", a.projectorFile)
        assertEquals(a, ArtifactId.unpinned("a".repeat(64), "b".repeat(64)))
        assertNotEquals(a, ArtifactId.unpinned("c".repeat(64), "b".repeat(64)))
        assertNotEquals(a, ArtifactId.unpinned("a".repeat(64), null))
        assertNull(ArtifactId.unpinned("a".repeat(64), null).projectorFile)
    }

    @Test
    fun a_file_hash_is_reused_only_while_the_file_is_unchanged() {
        val dir = Files.createTempDirectory("hashes").toFile()
        val model = java.io.File(dir, "model.gguf").apply { writeText("abc") }
        val hashes = FileHashes(java.io.File(dir, "hashes.json"))
        assertNull(hashes.known(model))
        val sha = hashes.hash(model)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
        assertEquals(sha, FileHashes(java.io.File(dir, "hashes.json")).known(model))

        // Same size, other bytes, a later time: unknown until hashed again.
        model.writeText("abd")
        model.setLastModified(model.lastModified() + 5_000)
        assertNull(hashes.known(model))
        assertNotEquals(sha, hashes.hash(model))
    }
}
