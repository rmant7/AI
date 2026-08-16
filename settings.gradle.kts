pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Last on purpose: the Android plugin lives here, everything else
        // resolves before this repository is ever queried.
        google()
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
