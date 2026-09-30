cmake_minimum_required(VERSION 3.22)
project(qwen3_tts_bridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Same reasoning as :whisper and :app's llama_jni: a debug APK compiles native
# code at -O0 by default, a tenfold slowdown for ggml's numeric kernels, and
# this project only ever ships a debug-signed APK.
foreach(cfg DEBUG RELWITHDEBINFO MINSIZEREL RELEASE)
    set(CMAKE_C_FLAGS_${cfg}   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_${cfg} "-O3 -DNDEBUG" CACHE STRING "" FORCE)
endforeach()
add_compile_options(-O3)

# Everything is hidden by default; only the JNI entry points (JNIEXPORT) are
# visible. Together with --exclude-libs below this keeps this library's
# statically linked ggml from clashing with the copies inside libllama_jni*.so
# and libwhisper_jni*.so.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

# qwen3-tts.cpp (https://github.com/Danmoreng/qwen3-tts.cpp) pinned to an exact
# commit; its own ggml is a git submodule (pinned by that commit) and is
# fetched with it. Populated rather than made available: the upstream root
# CMakeLists also builds a CLI, tests and a quantizer that this module does
# not want. A bare commit hash cannot be shallow-cloned, hence no GIT_SHALLOW.
#
# Like :whisper this is a separate Gradle module (its own CMake configure and
# .so) instead of a second add_subdirectory() in another module: each native
# library statically links its own ggml copy, at the cost of a few MB.
include(FetchContent)
FetchContent_Declare(
    qwen3ttscpp
    GIT_REPOSITORY https://github.com/Danmoreng/qwen3-tts.cpp.git
    GIT_TAG        16bb5afcd06311031c72a8488f8d59660dc2fb46
)
FetchContent_GetProperties(qwen3ttscpp)
if(NOT qwen3ttscpp_POPULATED)
    FetchContent_Populate(qwen3ttscpp)
endif()
set(QWEN3_TTS_ROOT "${qwen3ttscpp_SOURCE_DIR}")

# One source patch to upstream (applied to the fetched copy at configure time, idempotent, and a hard
# error if upstream no longer matches). ggml's CPU thread count is one process-wide setting on a shared
# backend, so "N threads" applied to the talker, the Code Predictor and the vocoder alike. Measured on
# a Pixel: the talker/Code Predictor are hundreds of tiny matrix-vector products per frame and get
# SLOWER with more threads (each operation pays ~70 us of thread synchronisation: 1 thread 0.03 ms,
# 4 threads 0.15 ms per 1024x1024 product), while the vocoder's big convolutions want many. This
# switches the thread count around every vocoder decode (voice priming, each frame push, the final
# flush) to the value given by qwen3_tts::phase_vocoder_threads() (defined in the bridge; 0 = no switch).
set(_pipeline_src "${QWEN3_TTS_ROOT}/src/pipeline/pipeline_synthesize.cpp")
file(READ "${_pipeline_src}" _src)
string(FIND "${_src}" "VocoderThreadScope" _already)
if(_already EQUAL -1)
    set(_helper [=[
// --- Android app patch: the streaming vocoder gets its own thread count -------------------------------
int32_t phase_vocoder_threads();  // defined by the host app; 0 = same thread count as the rest
namespace {
struct VocoderThreadScope {
    int32_t base_ = 0;
    bool explicit_ = false;
    bool active_ = false;
    VocoderThreadScope() {
        const int32_t vt = phase_vocoder_threads();
        const int32_t cur = get_cpu_thread_count();
        if (vt > 0 && vt != cur) {
            base_ = cur;
            explicit_ = cpu_thread_count_is_explicit();
            set_cpu_thread_count(vt, explicit_);
            active_ = true;
        }
    }
    ~VocoderThreadScope() {
        if (active_) set_cpu_thread_count(base_, explicit_);
    }
};
}  // namespace
// ---------------------------------------------------------------------------------------------------
]=])
    string(FIND "${_src}" "namespace qwen3_tts {\n" _ns_pos)
    if(_ns_pos EQUAL -1)
        message(FATAL_ERROR "qwen3-tts.cpp patch: 'namespace qwen3_tts {' not found in pipeline_synthesize.cpp")
    endif()
    math(EXPR _ns_end "${_ns_pos} + 22")
    string(SUBSTRING "${_src}" 0 ${_ns_end} _head)
    string(SUBSTRING "${_src}" ${_ns_end} -1 _tail)
    set(_src "${_head}${_helper}${_tail}")

    function(_q3_patch_replace from to)
        string(FIND "${_src}" "${from}" _at)
        if(_at EQUAL -1)
            message(FATAL_ERROR "qwen3-tts.cpp patch: expected text not found: ${from}")
        endif()
        string(REPLACE "${from}" "${to}" _out "${_src}")
        set(_src "${_out}" PARENT_SCOPE)
    endfunction()
    _q3_patch_replace(
        "return stream.push_frame(frame_codes);"
        "{ VocoderThreadScope vocoder_threads_scope; return stream.push_frame(frame_codes); }")
    _q3_patch_replace(
        "if (streaming && !stream.flush()) {"
        "if (streaming && !([&]() { VocoderThreadScope vocoder_threads_scope; return stream.flush(); })()) {")
    _q3_patch_replace(
        "if (!stream.preload_context(reference_codes_ptr->codes.data(), reference_codes_ptr->n_frames)) {"
        "if (!([&]() { VocoderThreadScope vocoder_threads_scope; return stream.preload_context(reference_codes_ptr->codes.data(), reference_codes_ptr->n_frames); })()) {")
    file(WRITE "${_pipeline_src}" "${_src}")
    message(STATUS "qwen3-tts.cpp: patched pipeline_synthesize.cpp (vocoder thread scope)")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
# Quantized matmuls are the whole cost of this model: dotprod+fp16 is what
# every phone from ~2019 has. The Kotlin side refuses to load this library on
# a CPU without those features (a SIGILL cannot be caught).
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory("${QWEN3_TTS_ROOT}/ggml" "${CMAKE_CURRENT_BINARY_DIR}/ggml")

find_package(Threads REQUIRED)

# Every upstream source except the CLI, the quantizer and upstream's own JNI
# layer (which is bound to another app's Kotlin class names) — the same set the
# reference Android app (Danmoreng/qwen3-tts-android) compiles.
file(GLOB_RECURSE QWEN3_TTS_SOURCES "${QWEN3_TTS_ROOT}/src/*.cpp")
list(FILTER QWEN3_TTS_SOURCES EXCLUDE REGEX ".*/(main|qwen3_tts_jni|qwen3_tts_quantize)\\.cpp$")

add_library(qwen3_tts_core STATIC ${QWEN3_TTS_SOURCES})
target_include_directories(qwen3_tts_core PUBLIC "${QWEN3_TTS_ROOT}/src" "${QWEN3_TTS_ROOT}/ggml/include")
target_compile_definitions(qwen3_tts_core PRIVATE QWEN3_TTS_EXPORT)
# Upstream's own per-frame profile (Talker vs Code Predictor, each split into graph build / alloc /
# compute / data I/O), printed to stderr after every generation and so shown on the result card.
# The cost is a few clock reads per graph call.
target_compile_definitions(qwen3_tts_core PUBLIC QWEN3_TTS_TIMING)
target_link_libraries(qwen3_tts_core PUBLIC ggml ggml-base ggml-cpu Threads::Threads)

add_library(qwen3_tts_bridge SHARED qwen3_tts_bridge.cpp ggml_matvec_bench.cpp)
find_library(log-lib log)
target_link_libraries(qwen3_tts_bridge PRIVATE qwen3_tts_core ${log-lib})
target_link_options(qwen3_tts_bridge PRIVATE "-Wl,--exclude-libs,ALL")
