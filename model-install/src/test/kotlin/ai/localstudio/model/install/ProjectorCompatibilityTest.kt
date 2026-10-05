package ai.localstudio.model.install

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectorCompatibilityTest {

    private fun header(build: GgufBytes.() -> GgufBytes) =
        GgufMetadataReader.read(ByteArrayInputStream(GgufBytes().build().bytes()))

    @Test
    fun a_vision_projector_of_a_known_type_is_usable() {
        val verdict = ProjectorCompatibility.of(header { string("general.architecture", "clip").bool("clip.has_vision_encoder", true).string("clip.projector_type", "gemma3") })
        assertEquals(ProjectorCompatibility.Vision("gemma3"), verdict)
    }

    @Test
    fun a_mixed_vision_and_audio_projector_is_read_by_its_vision_type() {
        val verdict = ProjectorCompatibility.of(
            header { string("general.architecture", "clip").bool("clip.has_vision_encoder", true).bool("clip.has_audio_encoder", true).string("clip.vision.projector_type", "qwen2.5vl_merger") },
        )
        assertEquals(ProjectorCompatibility.Vision("qwen2.5vl_merger"), verdict)
    }

    @Test
    fun audio_only_unknown_typeless_and_non_projector_files_are_not_usable() {
        fun reason(build: GgufBytes.() -> GgufBytes) = assertIs<ProjectorCompatibility.NotUsable>(ProjectorCompatibility.of(header(build))).reason
        assertTrue(reason { string("general.architecture", "clip").bool("clip.has_vision_encoder", false).string("clip.projector_type", "ultravox") }.contains("no vision encoder"))
        assertTrue(reason { string("general.architecture", "clip").string("clip.projector_type", "gemma3") }.contains("no vision encoder"), "absent means none, as in clip.cpp")
        assertTrue(reason { string("general.architecture", "clip").bool("clip.has_vision_encoder", true).string("clip.projector_type", "hologram9") }.contains("cannot load projector type \"hologram9\""))
        assertTrue(reason { string("general.architecture", "clip").bool("clip.has_vision_encoder", true) }.contains("no projector type"))
        assertTrue(reason { string("general.architecture", "llama").bool("clip.has_vision_encoder", true) }.contains("not a projector"))
    }
}
