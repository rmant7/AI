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
# Trade-off: a device older than ~2017 (ARMv8.0) would crash with SIGILL on this
# build. Upstream's answer is GGML_CPU_ALL_VARIANTS with runtime dispatch, which
# needs shared native libs and a loader call from Kotlin — worth doing once
# there is a device that old to test against.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(llamacpp)

add_library(llama_jni SHARED llama_jni.cpp)

target_include_directories(llama_jni PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/ggml/include
)

find_library(log-lib log)
target_link_libraries(llama_jni llama ggml ${log-lib})
