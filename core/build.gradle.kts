plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    mavenCentral()
    // Same JitPack repository :commercial-memory declares — required here
    // too, and must resolve to the exact same artifact coordinate/version:
    // see this file's own comment on api(...) below for why a mismatch
    // between the two would actually break the build, not just be untidy.
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // api, not implementation: MemoryProvider and friends are part of this
    // module's own public surface (NodeExecutors, Orchestrator), so anything
    // that depends on :core needs to see these types too. Must be the exact
    // same Mobile_mem0 artifact/version :commercial-memory depends on
    // (below) — NodeExecutors passes its own MemoryProvider instance into
    // MemoryExperimentRunner (see AppContainer), and the JVM treats
    // structurally-identical classes from two different jars as two
    // different types: a mismatch here is a real compile error in :app, not
    // a style inconsistency.
    api("com.github.rmant7:Mobile_mem0:v0.3.0-alpha3")
    // Same reasoning: MemoryExperimentRunner/ExperimentMode are part of
    // NodeExecutors' own constructor now (see memorySearch()).
    api(project(":commercial-memory"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    // Java 17 bytecode, not 21: these modules are consumed by the Android app,
    // and D8 is the constraint. Compiling with a newer JDK is fine; emitting
    // newer class files is not.
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
