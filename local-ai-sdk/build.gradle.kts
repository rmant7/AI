plugins {
    kotlin("jvm") version "2.1.0"
}

// The local-AI SDK's public surface: what an app (IntelliVerse first) calls
// to use on-device models -- which are installed, what each can do and what
// this device proved, text, translation and images in, discovery of new
// models. Nothing here names llama.cpp, a GGUF, a projector file or any
// other module of this repository: an implementation behind it can change
// without a caller noticing. Pure Kotlin, only coroutines (for Flow).

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    // Java 17 bytecode, same reason as :core -- consumed by Android apps, and
    // D8 is the constraint.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.test {
    useJUnitPlatform()
}
