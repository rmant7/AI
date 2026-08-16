pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Last on purpose: the Android plugin lives here, everything else
        // resolves before this repository is ever queried.
        google()
    }
    // Versions are declared, not applied: nothing is resolved until a project
    // actually asks for the plugin. The Kotlin plugin is deliberately absent —
    // it is already on the build classpath from the root project, and asking
    // for it again with a version is an error.
    plugins {
        id("com.android.application") version "8.7.3"
    }
}

rootProject.name = "local-ai-studio"

include(":core")
include(":openai")

// The Android app is included only where it can actually be built. `core` and
// `openai` stay buildable — and testable — on any JVM without the Android SDK,
// which is what keeps the architecture debuggable outside Android Studio.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").exists() ||
    startParameter.projectProperties.containsKey("withAndroid")

if (hasAndroidSdk) {
    include(":app")
}
