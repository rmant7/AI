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

# Same ARM tuning as :app's llama_jni — this project is developed and tested
# against one specific device (Tensor G5 / Pixel 10) confirmed to have
# dotprod, fp16 and i8mm; see that CMakeLists.txt's own comment for the
# hardware-floor trade-off this accepts.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${whispercpp_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}/ggml/include
)

find_library(log-lib log)
target_link_libraries(whisper_jni whisper ggml ${log-lib})
