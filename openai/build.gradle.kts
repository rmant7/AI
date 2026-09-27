plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    mavenCentral()
    // Tests only: EndToEndTest runs a real OpenAiRuntime through the full
    // memory-aware pipeline (NodeExecutors, FileMemoryStore), which lives in
    // :core-chat and pulls Mobile_mem0 from JitPack. Gradle resolves each
    // module's classpaths against its own repositories block, so this has
    // to be here for the test classpath — it adds nothing to what a
    // consumer of this module's main output needs. See MOBILE_MEM0_DEPENDENCY.md.
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // testImplementation, never implementation/api: :openai's own main
    // classpath must stay memory-free (see :core's build.gradle.kts).
    testImplementation(project(":core-chat"))
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
