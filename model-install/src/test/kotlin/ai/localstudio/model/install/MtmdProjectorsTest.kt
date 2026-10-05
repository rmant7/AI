package ai.localstudio.model.install

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MtmdProjectorsTest {

    /** Moving llama.cpp to another tag without regenerating the list would make every projector verdict stale. */
    @Test
    fun the_list_is_generated_from_the_llama_cpp_tag_the_app_builds() {
        val cmake = listOf("../llama-runtime/src/main/cpp/CMakeLists.txt", "llama-runtime/src/main/cpp/CMakeLists.txt").map(::File).first { it.isFile }
        val tag = Regex("""GIT_TAG\s+(\S+)""").find(cmake.readText())!!.groupValues[1]
        assertEquals(tag, MtmdProjectors.LLAMA_CPP_TAG, "CMakeLists.txt builds llama.cpp $tag: rerun scripts/gen_mtmd_projectors.py against that tag")
        assertEquals(LlamaCppArchitectures.LLAMA_CPP_TAG, MtmdProjectors.LLAMA_CPP_TAG)
    }

    @Test
    fun known_vision_projectors_are_there() {
        for (type in listOf("mlp", "gemma3", "gemma4v", "qwen2vl_merger", "qwen2.5vl_merger", "qwen3vl_merger", "idefics3", "lfm2")) {
            assertTrue(type in MtmdProjectors.LOADABLE, type)
        }
    }
}
