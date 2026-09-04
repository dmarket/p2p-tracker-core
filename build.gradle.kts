// Per-module configuration lives in the `dmarket.kmp.library` convention plugin (build-logic) and
// each module's build.gradle.kts. The Kotlin plugins are declared here with `apply false` so the
// Kotlin Gradle plugin (and its JS NodeJsRootPlugin) is loaded once on the root classpath and
// shared by every subproject — applying it per-subproject via the convention plugin otherwise
// trips "the Kotlin Gradle plugin was loaded multiple times".
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
