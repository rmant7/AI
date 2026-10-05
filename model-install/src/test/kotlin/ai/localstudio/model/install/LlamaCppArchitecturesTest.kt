package ai.localstudio.model.install

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlamaCppArchitecturesTest {

    /** Moving llama.cpp to another tag without regenerating the list would make every compatibility verdict stale. */
    @Test
    fun the_list_is_generated_from_the_llama_cpp_tag_the_app_builds() {
        val cmake = listOf("../llama-runtime/src/main/cpp/CMakeLists.txt", "llama-runtime/src/main/cpp/CMakeLists.txt").map(::File).first { it.isFile }
        val tag = Regex("""GIT_TAG\s+(\S+)""").find(cmake.readText())!!.groupValues[1]
        assertEquals(
            tag,
            LlamaCppArchitectures.LLAMA_CPP_TAG,
            "CMakeLists.txt builds llama.cpp $tag: rerun scripts/gen_llama_architectures.py against that tag",
        )
    }

    @Test
    fun known_families_are_there_and_placeholders_are_not() {
        for (arch in listOf("llama", "gemma3", "gemma4", "qwen3", "bert", "t5")) assertTrue(arch in LlamaCppArchitectures.LOADABLE, arch)
        assertFalse("clip" in LlamaCppArchitectures.LOADABLE)
        assertFalse("(unknown)" in LlamaCppArchitectures.LOADABLE)
    }
}
