plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    mavenCentral()
    // Resolves Mobile_mem0 below — see MOBILE_MEM0_DEPENDENCY.md. Every
    // memory-aware piece of this repository (this module, :commercial-memory,
    // and formerly :core itself before the split this module exists to make)
    // needs its own copy of this block: Gradle resolves each module's
    // compileClasspath against its own repositories {}, not whatever a
    // project() dependency's build script declared.
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // api, not implementation: NodeExecutors' own constructor exposes
    // MemoryProvider/MemoryExperimentRunner as part of its public signature,
    // so anything that constructs one (:app) needs to see these types too —
    // same reasoning :core's own former api(...) declarations had before
    // this module existed.
    api(project(":core"))
    api("com.github.rmant7:Mobile_mem0:v0.3.0-alpha3")
    api(project(":commercial-memory"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    // Java 17 bytecode, not 21: this module is consumed by the Android app,
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
