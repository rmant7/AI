cmake_minimum_required(VERSION 3.22)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Same reasoning as :app's llama_jni build: a debug APK compiles native code
# at -O0 by default, and for ggml's numeric kernels that is a tenfold slowdown
# over -O3. This project only ever ships a debug-signed APK.
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

# A separate Gradle module (and so a separate CMake configure) rather than a
# second add_subdirectory() inside :app's own llama_jni build — whisper.cpp
# vendors its own copy of ggml, and add_library(ggml ...) can only be defined
# once per CMake configure. Two independent configures, two independent .so
# files, each statically linking its own copy of ggml: a few extra MB in the
# APK next to a multi-hundred-MB model, in exchange for zero target-collision
# risk and zero cross-repo ggml-version-compatibility risk between whichever
# commit :app's llama.cpp FetchContent happens to pin and whichever this one does.
include(FetchContent)
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.9.3
    GIT_SHALLOW    TRUE
)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)

# Same three CPU-feature variants as :app's llama_jni, for the same reasons —
# see that CMakeLists.txt. :whisper builds the baseline; :whisper-dotprod and
# :whisper-i8mm (native-variants/) point at this file with a different
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
    set(LOCALAI_JNI_LIB whisper_jni)
else()
    set(LOCALAI_JNI_LIB whisper_jni_${LOCALAI_CPU_VARIANT})
endif()
message(STATUS "whisper_jni: variant=${LOCALAI_CPU_VARIANT} arch='${GGML_CPU_ARM_ARCH}' -> lib${LOCALAI_JNI_LIB}.so")

FetchContent_MakeAvailable(whispercpp)

add_library(${LOCALAI_JNI_LIB} SHARED whisper_jni.cpp)

# 16 KB memory pages (Android 15+ devices; Google Play) -- the same as
# llama_jni's: whisper/ggml are static here, this .so is the only one to align.
target_link_options(${LOCALAI_JNI_LIB} PRIVATE "-Wl,-z,max-page-size=16384")

target_include_directories(${LOCALAI_JNI_LIB} PRIVATE
    ${whispercpp_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}/ggml/include
)

find_library(log-lib log)
target_link_libraries(${LOCALAI_JNI_LIB} whisper ggml ${log-lib})
