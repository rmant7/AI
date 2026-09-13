plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

repositories {
    mavenCentral()
    // Resolves Mobile_mem0 below — JitPack builds any tagged commit of a
    // GitHub repo with no further setup on that repo's side (it already has
    // maven-publish configured; see its own build.gradle.kts and README).
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // The real, external Mobile_mem0 artifact — no longer the project(":memory")
    // stand-in this module used before v0.1.0 was tagged (see
    // MOBILE_MEM0_DEPENDENCY.md at the repo root for that history). This
    // module depends on nothing from :core or :app either way: it must stay
    // swappable for a stronger private ranker without the rest of the
    // application, or Mobile_mem0 itself, ever noticing.
    implementation("com.github.rmant7:Mobile_mem0:v0.3.0-alpha2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
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
