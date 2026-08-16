plugins {
    // Both versions are declared here so AGP and the Kotlin Android plugin land
    // in the same classpath — the Kotlin plugin needs AGP's classes to apply.
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
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

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
