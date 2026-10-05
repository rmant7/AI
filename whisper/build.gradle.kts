plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ai.localstudio.whisper"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // Matches :app — gradle.properties' localai.abis (arm64 by default,
        // x86_64 only for CI's emulator smoke test).
        ndk {
            abiFilters += providers.gradleProperty("localai.abis").orElse("arm64-v8a").get()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // WhisperBridge.nativeOpMutex (same reasoning as llama's own bridge) —
    // this module had no dependencies block at all before that existed.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // CpuVariant (picks the dotprod/i8mm build of whisper_jni) lives in :core, shared with :llama-runtime.
    implementation(project(":core"))
}
