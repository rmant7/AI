// llama_jni built with LOCALAI_CPU_VARIANT=i8mm — see app/src/main/cpp/CMakeLists.txt.
// No sources of its own: the same CMakeLists.txt, a different CMake argument,
// a differently named .so (libllama_jni_i8mm.so) that :app packages alongside the
// baseline and ai.localstudio.whisper.CpuVariant picks at runtime.
plugins {
    id("com.android.library") version "8.7.3"
}

repositories {
    google()
    mavenCentral()
}

// arm64-only: these variants are CPU-feature builds of ARM code. Skipped
// entirely when a build targets other ABIs only (CI's x86_64 emulator run).
val buildsArm64 = "arm64-v8a" in providers.gradleProperty("localai.abis").orElse("arm64-v8a").get()
    .split(',').map { it.trim() }

android {
    namespace = "ai.localstudio.nativevariant.llama_i8mm"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        if (buildsArm64) {
            ndk {
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=c++_shared", "-DLOCALAI_CPU_VARIANT=i8mm")
                }
            }
        }
    }

    if (buildsArm64) {
        externalNativeBuild {
            cmake {
                path = file("../../app/src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}
