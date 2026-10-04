plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

// From a catalog entry to an installed, verified variant a runtime can load:
// artifact resolution, transfer (resume, retry, source fallback),
// verification, unpacking, the install layout and its manifest, runtime
// binding selection and storage/memory admission. Pure Kotlin like
// :model-core — the network (Hugging Face metadata, HTTP) and the device
// (free space, memory, CPU features) come in through small interfaces the
// app implements, so the whole chain is testable on a plain JVM.

repositories {
    mavenCentral()
}

dependencies {
    api(project(":model-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    // Only to prove FileSelection picks exactly what the legacy
    // ArtifactResolver.pickBest picks.
    testImplementation(project(":core"))
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
    // The Phase 2 catalogue, installed end to end against fakes in InstallerTest.
    val legacyCatalog = rootProject.file("model-catalog/local-models.json")
    inputs.file(legacyCatalog).withPropertyName("legacyCatalog")
    systemProperty("localai.legacyCatalog", legacyCatalog.absolutePath)
}
