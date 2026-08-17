plugins {
    // Both versions are declared here so AGP and the Kotlin Android plugin land
    // in the same classpath — the Kotlin plugin needs AGP's classes to apply.
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    google()
    mavenCentral()
}

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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
