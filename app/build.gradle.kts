plugins {
    // Both versions are declared here so AGP and the Kotlin Android plugin land
    // in the same classpath — the Kotlin plugin needs AGP's classes to apply.
    id("com.android.application") version "8.7.3"
    // Bumped from 2.1.0 for this module only: the Google Tensor SDK AAR
    // (litertlm-android) ships Kotlin metadata compiled with 2.4.0, which
    // 2.1.0's compiler cannot even read ("Internal compiler error" trying to
    // analyse the module) — core/openai don't depend on that AAR and stay on
    // 2.1.0, since there is no reason to move their already-tested Kotlin
    // version just because this module now needs a newer one.
    id("org.jetbrains.kotlin.android") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

repositories {
    google()
    mavenCentral()
}

// versionName/versionCode are static and near-useless for "is this actually
// the build I was just sent" — every APK this project has ever produced
// carries the same "0.1"/1. The commit this APK was built from is the one
// thing that actually answers that question, so it gets baked in directly
// rather than relying on anyone to remember to bump a version number.
val gitSha: String = runCatching {
    ProcessBuilder("git", "rev-parse", "--short=10", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .let { process -> process.inputStream.bufferedReader().readText().trim() to process.waitFor() }
        .let { (output, exitCode) -> if (exitCode == 0) output else "unknown" }
}.getOrDefault("unknown")

// Correlates with the "apk-N" GitHub Release tag CI publishes under.
val ciRun: String = System.getenv("GITHUB_RUN_NUMBER") ?: "local"

android {
    namespace = "ai.localstudio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.localstudio.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "CI_RUN", "\"$ciRun\"")

        ndk {
            // arm64 is every phone worth running a model on; x86_64 exists so
            // the emulator in CI runs the same native code the device does.
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    // A fixed debug key, committed on purpose. AGP generates a throwaway
    // debug keystore when none exists, and CI starts from a clean home every
    // run — so consecutive builds were signed with different keys and Android
    // refused to install one over the other ("App not installed"). This key
    // guards nothing; it exists so that build N+1 upgrades build N.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The model catalog is a repository asset, not a copy: the Models screen
    // reads exactly the file the tests validate.
    sourceSets["main"].assets.srcDir(rootProject.file("registry"))

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
}

// android.kotlinOptions (String-based jvmTarget) is a hard error under the
// Kotlin 2.4.0 plugin — this is its replacement, same as core/openai already use.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":openai"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Text-file / PDF attachment → RAG context.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Renders the assistant's markdown (lists, **bold**, code) as formatted
    // text instead of the raw asterisks and hashes an LLM's output is full of.
    implementation("io.noties.markwon:core:4.6.2")

    // On-device speech-to-text (Whisper, TFLite).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Google's LiteRT-LM, via the Google Tensor SDK — a second local
    // inference path alongside llama.cpp, the only one able to run on a
    // Pixel's TPU/NPU instead of the CPU. Prebuilt AAR: no CMake/NDK build
    // of its own, unlike llama.cpp's FetchContent-based native build below.
    //
    // Pinned, not `latest.release`: on-device testing against whatever the
    // newest release happened to be (0.17.0) failed loading every Gemma
    // model with "Failed to create engine: NOT_FOUND ...
    // TF_LITE_PREFILL_DECODE not found in the model" — on two different
    // models, with the correct file confirmed, so not a download problem.
    // Google's own production app (google-ai-edge/gallery) pins 0.11.0
    // for this exact dependency — the version its own model downloads are
    // actually validated against — rather than tracking latest, so this
    // matches that instead of guessing at a newer one being more correct.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
