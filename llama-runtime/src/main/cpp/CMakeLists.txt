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
# The CMAKE_<LANG>_FLAGS_<CONFIG> overrides above do not reach the compile
# lines (a device crash showed ggml's plain assert() in vec.cpp still compiled
# in: "assertion !isnan(sumf) && !isinf(sumf) failed", SIGABRT on a model whose
# fp16 attention overflowed). Defined directly so it applies to every target,
# llama.cpp/ggml included: an overflowing model then gives a wrong answer
# instead of killing the app, as in any release build of llama.cpp.
add_compile_definitions(NDEBUG)
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
# Three CPU-feature variants of this library, one per configure, selected at
# runtime from Kotlin (see ai.localstudio.core.runtime.CpuVariant):
#
#   baseline  armv8-a                        libllama_jni.so          any arm64 CPU
#   dotprod   armv8.2-a+dotprod+fp16         libllama_jni_dotprod.so  ~Snapdragon 845+, Cortex-A55/A75+
#   i8mm      armv8.2-a+dotprod+fp16+i8mm    libllama_jni_i8mm.so     ~Snapdragon 8 Gen 1+, Tensor G3+
#
# Why not one build: compiling with a feature flag lets the compiler and
# ggml's repack path emit those instructions anywhere, regardless of what the
# CPU supports at runtime. A single +i8mm build died with SIGILL inside
# ggml_gemm_q4_K_8x8_q8_K on a Samsung Galaxy S20 FE (Snapdragon 865: dotprod
# and fp16, no i8mm); a single dotprod build would do the same on any
# ARMv8.0 phone, while a single baseline build gives up the int8 kernels that
# dominate prompt processing on newer ones.
#
# Why separate configures rather than GGML_CPU_ALL_VARIANTS: that needs ggml
# as shared libraries loaded through its backend registry, and :whisper
# vendors its own, different ggml version — two libggml.so with the same
# name in one APK. Separate configures keep every variant a self-contained
# .so with its own static ggml, exactly like the single build before.
#
# :app's own configure builds the baseline (and, for the x86_64 emulator
# build CI's smoke test uses, the only variant); :llama-dotprod and
# :llama-i8mm (native-variants/) point at this same file with a different
# LOCALAI_CPU_VARIANT.
set(LOCALAI_CPU_VARIANT "baseline" CACHE STRING "baseline | dotprod | i8mm")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    if(LOCALAI_CPU_VARIANT STREQUAL "i8mm")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    elseif(LOCALAI_CPU_VARIANT STREQUAL "dotprod")
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    elseif(LOCALAI_CPU_VARIANT STREQUAL "baseline")
        set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
    else()
        message(FATAL_ERROR "Unknown LOCALAI_CPU_VARIANT '${LOCALAI_CPU_VARIANT}'")
    endif()
endif()
if(LOCALAI_CPU_VARIANT STREQUAL "baseline")
    set(LOCALAI_JNI_LIB llama_jni)
else()
    set(LOCALAI_JNI_LIB llama_jni_${LOCALAI_CPU_VARIANT})
endif()
message(STATUS "llama_jni: variant=${LOCALAI_CPU_VARIANT} arch='${GGML_CPU_ARM_ARCH}' -> lib${LOCALAI_JNI_LIB}.so")

FetchContent_MakeAvailable(llamacpp)

# mtmd (multimodal: image/audio input) lives under tools/, which this project
# never adds as a whole — LLAMA_BUILD_TOOLS is OFF above, deliberately, to
# skip llama-cli/llama-server/the rest of the CLI tools this app has no use
# for. The mtmd *library* target itself has no such gate in its own
# CMakeLists.txt (only its CLI executables — llama-mtmd-cli and friends — do,
# behind that same LLAMA_BUILD_TOOLS), so it is added directly here instead
# of pulling in everything else under tools/ just to reach it.
add_subdirectory(${llamacpp_SOURCE_DIR}/tools/mtmd ${CMAKE_CURRENT_BINARY_DIR}/mtmd-build)

add_library(${LOCALAI_JNI_LIB} SHARED llama_jni.cpp)

# 16 KB memory pages (Android 15+ devices; Google Play rejects a library
# without it -- IntelliVerse's first real-signing-key upload, which builds this
# same file). llama/ggml/mtmd are static (BUILD_SHARED_LIBS OFF): this .so is
# the only shared object here, the only one whose ELF segments need aligning.
target_link_options(${LOCALAI_JNI_LIB} PRIVATE "-Wl,-z,max-page-size=16384")

target_include_directories(${LOCALAI_JNI_LIB} PRIVATE
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/ggml/include
    ${llamacpp_SOURCE_DIR}/tools/mtmd
)

find_library(log-lib log)
target_link_libraries(${LOCALAI_JNI_LIB} llama ggml mtmd ${log-lib})
