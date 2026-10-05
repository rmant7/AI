plugins {
    id("com.android.library") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

// The on-device llama.cpp runtime on its own: the JNI bridge and its native
// build (llama_jni + the dotprod/i8mm variants, picked at runtime by
// CpuVariant), LlamaCppRuntime (load, generate, images through a vision
// projector, the weights load mode), RamMeasuringRuntime and
// MeasuredRamStore (what a model really costs here). Out of :app so any app
// -- IntelliVerse first -- runs local models on the very same engine,
// admitted by the same RuntimeManager (:core). Package names are unchanged
// (ai.localstudio.app.llama): the JNI symbols are named after them.

repositories {
    google()
    mavenCentral()
}

val localAiAbis = providers.gradleProperty("localai.abis").orElse("arm64-v8a").get()
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "ai.localstudio.llama"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // arm64 by default, x86_64 only for CI's emulator smoke test -- gradle.properties' localai.abis, as :app.
        ndk {
            abiFilters += localAiAbis
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
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // The dotprod and i8mm builds of llama_jni: packaged next to the baseline wherever this module goes.
    implementation(project(":llama-dotprod"))
    implementation(project(":llama-i8mm"))
}
