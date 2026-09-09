plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    mavenCentral()
}

dependencies {
    // api, not implementation: MemoryProvider and friends are part of this
    // module's own public surface (NodeExecutors, Orchestrator), so anything
    // that depends on :core needs to see :memory's types too.
    api(project(":memory"))
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
