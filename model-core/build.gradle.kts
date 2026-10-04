plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

// The unified local-model domain: what a model is (definition / variant /
// artifacts / runtime bindings / capabilities), how a catalog of them is
// written down, and how a requirement is matched against them. Pure Kotlin
// on purpose, same constraint as :core: everything here must be testable on
// a plain JVM, and must stay consumable by an app (IntelliVerse) that wants
// local model management without pulling in chat, routing or pipelines.
//
// Phase 1 of the model-management unification: nothing depends on this
// module yet. Downloading, installation, runtime integration and the
// migration of the existing per-type seeds all come in later phases.
repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    // Java 17 bytecode, same reason as :core — consumed by Android apps, and
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
