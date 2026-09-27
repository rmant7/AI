plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

// Deliberately no JitPack repository here anymore. Everything that used to
// need Mobile_mem0 (NodeExecutors and the LlmMemoryExtractor it uses) moved
// to :core-chat specifically so :core stays buildable against nothing but
// plain Kotlin: cloud/local model routing, the error taxonomy, the model
// catalog and the SINGLE/COMPARE execution engine have no real use for
// memory retrieval, and a future consumer that only wants those (a
// translation-only IntelliVerse integration, for instance) should never
// have to pull in Mobile_mem0, :commercial-memory or their own JitPack
// resolution just to compile against this module. See MOBILE_MEM0_DEPENDENCY.md.
repositories {
    mavenCentral()
}

dependencies {
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
