cmake_minimum_required(VERSION 3.22)
project(llama_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# A debug APK compiles native code with CMAKE_BUILD_TYPE=Debug, and for Clang
# that means -O0: no inlining, no auto-vectorisation. For ordinary app code that
# is invisible; for ggml, which is almost entirely tight numeric kernels, -O0
# against -O3 is a tenfold difference — the difference between "a few seconds"
# and "several minutes" per answer. It also silently defeats the ARM flags set
# below, because unoptimised code never vectorises those kernels at all.
#
# This project ships a debug-signed APK as its only build, so optimisation is
# forced back up for every configuration.
foreach(cfg DEBUG RELWITHDEBINFO MINSIZEREL RELEASE)
    set(CMAKE_C_FLAGS_${cfg}   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
    set(CMAKE_CXX_FLAGS_${cfg} "-O3 -DNDEBUG" CACHE STRING "" FORCE)
endforeach()
add_compile_options(-O3)
message(STATUS "llama_jni: CMAKE_BUILD_TYPE='${CMAKE_BUILD_TYPE}' -> forcing -O3")

include(FetchContent)
FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b10448
    GIT_SHALLOW    TRUE
)

# Cross-compiling to Android: never let ggml probe the *host* CPU, and skip the
# parts of llama.cpp an embedded JNI build does not need.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

# With GGML_NATIVE off and no explicit ARM arch, ggml appends no -march flags at
# all and falls back to baseline armv8-a — without dotprod and fp16, which the
# quantised matmul kernels (the dominant cost of inference) rely on. Setting it
# explicitly is what makes on-device generation usable rather than a curiosity.
#
# No i8mm, deliberately. It was here (int8 matrix-multiply kernels, a real
# prompt-processing speed-up on Tensor G5 / Pixel 10), with a comment
# accepting that "a device from roughly 2018-2020 that has dotprod/fp16 but
# not i8mm would still SIGILL on this build". Real device report: exactly
# that — a Samsung Galaxy S20 FE (Snapdragon 865, Cortex-A77: ARMv8.2 with
# dotprod and fp16, no i8mm) died with SIGILL/ILL_ILLOPC inside
# ggml_gemm_q4_K_8x8_q8_K on its first local generation. Compiling with +i8mm
# lets the compiler and ggml's repack path use those instructions anywhere,
# regardless of what the CPU reports at runtime, so there is no safe way to
# keep it in a single build.
#
# The hardware floor is now ARMv8.2-A with dotprod and fp16 (roughly
# Snapdragon 855 / Cortex-A76 and newer). Getting i8mm back on devices that
# have it needs runtime dispatch — two builds of this library, picked from
# Kotlin by the CPU's reported features — not a wider compile flag.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(llamacpp)

# mtmd (multimodal: image/audio input) lives under tools/, which this project
# never adds as a whole — LLAMA_BUILD_TOOLS is OFF above, deliberately, to
# skip llama-cli/llama-server/the rest of the CLI tools this app has no use
# for. The mtmd *library* target itself has no such gate in its own
# CMakeLists.txt (only its CLI executables — llama-mtmd-cli and friends — do,
# behind that same LLAMA_BUILD_TOOLS), so it is added directly here instead
# of pulling in everything else under tools/ just to reach it.
add_subdirectory(${llamacpp_SOURCE_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd-build)

add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/ggml/include
    ${llamacpp_SOURCE_DIR}/tools/mtmd
)

find_library(log-lib log)
target_link_libraries(llama_jni llama ggml mtmd ${log-lib})
