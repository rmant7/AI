package ai.localstudio.model.install

import ai.localstudio.model.ArtifactRoles
import ai.localstudio.model.ArtifactSource
import ai.localstudio.model.Capabilities
import ai.localstudio.model.CatalogStatus
import ai.localstudio.model.GenericFacet
import ai.localstudio.model.RuntimeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CandidateVerificationTest {

    private val artifact = ArtifactId("acme/a-GGUF", "a".repeat(40), "a-Q4_K_M.gguf")
    private val here = VerificationContext(artifact, "Google Pixel 10 Pro", "llama.cpp b10448 / jni 2")

    @Test
    fun a_candidate_with_no_verification_is_unverified() {
        assertEquals(CandidateTier.UNVERIFIED, null.tier(here))
    }

    private fun verification(
        loaded: Boolean,
        inferenceOk: Boolean,
        checkVersion: Int = DeviceVerification.CURRENT_CHECK,
        checks: Map<String, CapabilityCheck> = if (inferenceOk) mapOf(VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.PASS)) else emptyMap(),
        context: VerificationContext? = here,
    ) = DeviceVerification(
        deviceProfile = "Pixel 10 Pro / API 37 / 16.3 GB / llama.cpp b10448 (i8mm)",
        runtimeId = "llama_cpp",
        loaded = loaded,
        inferenceOk = inferenceOk,
        verifiedAtEpochMs = 1_000L,
        checkVersion = checkVersion,
        checks = checks,
        artifact = context?.artifact,
        device = context?.device,
        runtimeVersion = context?.runtimeVersion,
    )

    @Test
    fun a_translation_model_failing_chat_questions_is_functional_for_translation_only() {
        val v = verification(
            loaded = true, inferenceOk = true,
            checks = mapOf(
                VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.FAIL, "wrong answer"),
                VerifiedCapability.TRANSLATION to CapabilityCheck(CheckStatus.PASS),
            ),
        )
        assertEquals(CandidateTier.FUNCTIONAL, v.tier(here))
        assertEquals(listOf(VerifiedCapability.TRANSLATION), v.passed(here))
        assertEquals(CheckStatus.NOT_TESTED, v.status(VerifiedCapability.VISION, here))
        assertEquals(CheckStatus.FAIL, v.status(VerifiedCapability.TEXT, here))
    }

    @Test
    fun a_pass_is_valid_only_for_the_same_bytes_device_runtime_and_questions() {
        val v = verification(loaded = true, inferenceOk = true)
        assertEquals(CheckStatus.PASS, v.status(VerifiedCapability.TEXT, here))
        assertEquals(null, v.staleReason(here))

        val changes = mapOf(
            "other main file" to here.copy(artifact = artifact.copy(mainFile = "a-Q8_0.gguf")),
            "other commit" to here.copy(artifact = artifact.copy(revision = "b".repeat(40))),
            "a projector added" to here.copy(artifact = artifact.copy(projectorFile = "mmproj-F16.gguf")),
            "other device" to here.copy(device = "samsung SM-G781B"),
            "other llama.cpp" to here.copy(runtimeVersion = "llama.cpp b11000 / jni 2"),
            "other JNI revision" to here.copy(runtimeVersion = "llama.cpp b10448 / jni 3"),
            "other questions" to here.copy(checkVersion = DeviceVerification.CURRENT_CHECK + 1),
        )
        for ((what, now) in changes) {
            assertEquals(CheckStatus.STALE, v.status(VerifiedCapability.TEXT, now), what)
            assertEquals(false, v.passes(VerifiedCapability.TEXT, now), what)
            assertEquals(CandidateTier.UNVERIFIED, v.tier(now), "$what: proves nothing here, not even a load")
            assertEquals(true, v.staleReason(now) != null, what)
            assertEquals(CheckStatus.NOT_TESTED, v.status(VerifiedCapability.VISION, now), "$what: never tested stays never tested, not stale")
            assertEquals(CheckStatus.PASS, v.recorded(VerifiedCapability.TEXT), "$what: the record itself is kept")
        }
    }

    @Test
    fun a_pass_recorded_by_the_previous_check_version_is_stale() {
        // Version 2 judged translation with a chat-style prompt the Translation screen never sends.
        val v = verification(loaded = true, inferenceOk = true, checkVersion = 2)
        assertEquals(CandidateTier.UNVERIFIED, v.tier(here))
        assertEquals(emptyList(), v.passed(here))
        assertEquals(CheckStatus.STALE, v.status(VerifiedCapability.TEXT, here))
    }

    @Test
    fun a_record_from_before_contexts_were_kept_is_stale_not_absent() {
        val v = verification(loaded = true, inferenceOk = true, context = null)
        assertEquals(CheckStatus.STALE, v.status(VerifiedCapability.TEXT, here))
        assertEquals(true, v.staleReason(here)!!.contains("before"))
    }

    @Test
    fun answering_without_any_capability_passing_is_loadable() {
        val v = verification(
            loaded = true, inferenceOk = false,
            checks = mapOf(VerifiedCapability.TEXT to CapabilityCheck(CheckStatus.FAIL), VerifiedCapability.TRANSLATION to CapabilityCheck(CheckStatus.FAIL)),
        )
        assertEquals(CandidateTier.LOADABLE, v.tier(here))
    }

    @Test
    fun an_answer_judged_by_an_older_check_is_not_trusted_as_functional() {
        assertEquals(CandidateTier.UNVERIFIED, verification(loaded = true, inferenceOk = true, checkVersion = 1).tier(here))
        assertEquals(CandidateTier.UNVERIFIED, verification(loaded = false, inferenceOk = true, checkVersion = 1).tier(here))
    }

    @Test
    fun a_record_written_before_check_versions_existed_reads_as_the_first_check() {
        val old = kotlinx.serialization.json.Json.decodeFromString(
            DeviceVerification.serializer(),
            """{"deviceProfile":"p","runtimeId":"llama_cpp","loaded":true,"inferenceOk":true,"verifiedAtEpochMs":1}""",
        )
        assertEquals(1, old.checkVersion)
        assertEquals(null, old.context)
        assertEquals(CandidateTier.UNVERIFIED, old.tier(here))
    }

    @Test
    fun a_load_failure_is_unverified_not_loadable() {
        assertEquals(CandidateTier.UNVERIFIED, verification(loaded = false, inferenceOk = false).tier(here))
    }

    @Test
    fun loaded_but_no_answer_is_loadable_not_functional() {
        assertEquals(CandidateTier.LOADABLE, verification(loaded = true, inferenceOk = false).tier(here))
    }

    @Test
    fun a_real_answer_is_functional() {
        assertEquals(CandidateTier.FUNCTIONAL, verification(loaded = true, inferenceOk = true).tier(here))
    }

    @Test
    fun inference_ok_implies_functional_even_if_loaded_was_recorded_wrong() {
        // inferenceOk can only be true after a real load, so it alone decides --
        // a caller's own bug in setting `loaded` must never hide a real answer.
        assertEquals(CandidateTier.FUNCTIONAL, verification(loaded = false, inferenceOk = true).tier(here))
    }

    @Test
    fun the_install_model_pins_the_exact_file_discovery_already_probed_never_quant_priority() {
        val model = CandidateModel.of(
            ModelArtifact(ModelFile("acme/small-GGUF", "a".repeat(40), "small-Q4_K_M.gguf", 2_000_000_000, "b".repeat(64))),
            variantId = "discovered-acme-small",
            runtime = RuntimeId("llama_cpp"),
        )

        assertEquals(CatalogStatus.UNVERIFIED, model.status, "a discovery candidate is never anything else")
        val variant = model.variants.single()
        val weights = variant.artifacts.single { it.role == ArtifactRoles.WEIGHTS }
        val source = assertIs<ArtifactSource.HuggingFace>(weights.source)
        assertEquals("acme/small-GGUF", source.repo)
        assertEquals("a".repeat(40), source.revision, "the commit discovery resolved, not a branch to re-resolve later")
        assertEquals("small-Q4_K_M.gguf", source.path, "the exact file GgufProbe read, never re-picked by quant priority")
        assertEquals(2_000_000_000, weights.sizeBytes)
        assertEquals("b".repeat(64), weights.sha256)
        assertEquals(setOf(ArtifactRoles.WEIGHTS), variant.bindings.single().requiredRoles)
    }

    private val main = ModelFile("acme/see-GGUF", "a".repeat(40), "see-Q4_K_M.gguf", 2_000_000_000, "b".repeat(64))

    @Test
    fun a_projector_from_another_repository_or_commit_is_not_part_of_the_model() {
        val elsewhere = ProjectorFile(main.copy(repo = "other/see-GGUF", path = "mmproj-F16.gguf"), "gemma3")
        val otherCommit = ProjectorFile(main.copy(revision = "c".repeat(40), path = "mmproj-F16.gguf"), "gemma3")
        kotlin.test.assertFailsWith<IllegalArgumentException> { ModelArtifact(main, elsewhere) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { ModelArtifact(main, otherCommit) }
    }

    @Test
    fun an_artifact_id_names_exactly_the_bytes_main_file_and_projector() {
        val main = ModelFile("acme/see-GGUF", "a".repeat(40), "see-Q4_K_M.gguf", 2_000_000_000)
        val pair = ModelArtifact(main, ProjectorFile(main.copy(path = "mmproj-F16.gguf", sizeBytes = 800_000_000), "gemma3"))
        assertEquals(ArtifactId("acme/see-GGUF", "a".repeat(40), "see-Q4_K_M.gguf", "mmproj-F16.gguf"), pair.id)
        assertEquals("acme/see-GGUF|${"a".repeat(40)}|see-Q4_K_M.gguf|mmproj-F16.gguf", pair.id.key)
        assertEquals("acme/see-GGUF|${"a".repeat(40)}|see-Q4_K_M.gguf", ModelArtifact(main).id.key)
        kotlin.test.assertNotEquals(pair.id, ModelArtifact(main).id, "the same main file without its projector is another artifact")
    }

    @Test
    fun an_install_names_the_same_artifact_discovery_did_or_none_at_all() {
        val commit = "a".repeat(40)
        fun file(role: ai.localstudio.model.ArtifactRole, path: String, repo: String? = "acme/see-GGUF", at: String? = commit) = InstalledArtifact(
            role = role, fileName = path, sizeBytes = 1, sha256 = "0", integrity = IntegrityBasis.values().first(),
            source = SourceRecord(url = "https://huggingface.co/$repo/resolve/$at/$path", repo = repo, commit = at, path = path),
        )
        fun manifest(vararg files: InstalledArtifact) = InstallManifest(
            catalogId = "c", catalogVersion = "1", modelId = ai.localstudio.model.ModelId("m"), variantId = ai.localstudio.model.VariantId("v"),
            installedAtEpochMs = 1, artifacts = files.toList(),
        )
        val main = ModelFile("acme/see-GGUF", commit, "see-Q4_K_M.gguf", 2_000_000_000)
        val pair = ModelArtifact(main, ProjectorFile(main.copy(path = "mmproj-F16.gguf"), "gemma3"))

        assertEquals(pair.id, manifest(file(ArtifactRoles.WEIGHTS, "see-Q4_K_M.gguf"), file(ArtifactRoles.PROJECTOR, "mmproj-F16.gguf")).artifactId())
        assertEquals(ModelArtifact(main).id, manifest(file(ArtifactRoles.WEIGHTS, "see-Q4_K_M.gguf")).artifactId())
        assertEquals(null, manifest(file(ArtifactRoles.WEIGHTS, "see-Q4_K_M.gguf", at = null)).artifactId(), "no commit recorded: no identity")
        assertEquals(
            null,
            manifest(file(ArtifactRoles.WEIGHTS, "see-Q4_K_M.gguf"), file(ArtifactRoles.PROJECTOR, "mmproj-F16.gguf", repo = "other/repo")).artifactId(),
            "a projector from elsewhere is not this artifact's",
        )
    }

    @Test
    fun vision_needs_the_main_model_and_its_projector_text_needs_the_main_model() {
        val textOnly = ModelArtifact(main)
        val pair = ModelArtifact(main, ProjectorFile(main.copy(path = "mmproj-F16.gguf", sizeBytes = 800_000_000), "gemma3"))
        assertEquals(false, textOnly.canCheck(VerifiedCapability.VISION))
        assertEquals(true, textOnly.canCheck(VerifiedCapability.TEXT))
        assertEquals(true, pair.canCheck(VerifiedCapability.VISION))
        assertEquals(2_800_000_000, pair.totalBytes)
    }

    @Test
    fun the_install_model_of_a_pair_is_one_model_with_two_roles_pinned_to_one_commit() {
        val pair = ModelArtifact(main, ProjectorFile(main.copy(path = "mmproj-F16.gguf", sizeBytes = 800_000_000, sha256 = "d".repeat(64)), "gemma3"))
        val model = CandidateModel.of(pair, "discovered-acme-see", RuntimeId("llama_cpp"))

        val variant = model.variants.single()
        assertEquals(setOf(ArtifactRoles.WEIGHTS, ArtifactRoles.PROJECTOR), variant.artifacts.map { it.role }.toSet())
        val projector = variant.artifacts.single { it.role == ArtifactRoles.PROJECTOR }
        assertEquals(false, projector.optional, "installed and checked as the pair, or not at all")
        val source = assertIs<ArtifactSource.HuggingFace>(projector.source)
        assertEquals("a".repeat(40), source.revision)
        assertEquals("mmproj-F16.gguf", source.path)
        assertEquals("d".repeat(64), projector.sha256)
        assertEquals(setOf(ArtifactRoles.PROJECTOR), model.capabilities.getValue(Capabilities.VISION).requiresRoles)
        assertEquals(setOf(ArtifactRoles.PROJECTOR), variant.bindings.single().optionalRoles)

        val text = CandidateModel.of(ModelArtifact(main), "discovered-acme-plain", RuntimeId("llama_cpp"))
        assertEquals(false, Capabilities.VISION in text.capabilities)
        assertEquals(listOf(ArtifactRoles.WEIGHTS), text.variants.single().artifacts.map { it.role })
    }
}

