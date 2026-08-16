// Intentionally empty.
//
// Declaring a plugin here — even with `apply false` — puts it on the build
// classpath of every project. The Kotlin plugin loaded that way cannot see the
// Android plugin, which is resolved per-project, and applying
// `org.jetbrains.kotlin.android` fails with a missing AGP class. Each module
// therefore declares what it needs, and the JVM-only modules never mention the
// Android plugin at all.
