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
target_link_libraries(qwen3_tts_core PUBLIC ggml ggml-base ggml-cpu Threads::Threads)

add_library(qwen3_tts_bridge SHARED qwen3_tts_bridge.cpp)
find_library(log-lib log)
target_link_libraries(qwen3_tts_bridge PRIVATE qwen3_tts_core ${log-lib})
target_link_options(qwen3_tts_bridge PRIVATE "-Wl,--exclude-libs,ALL")
