plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
}

// Plugin-marker dependencies so the precompiled convention plugins can apply these by id.
// The first four are pinned here; the Android and publishing plugins come from the root catalog
// (build-logic/settings.gradle.kts imports it).
dependencies {
    implementation("org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:2.4.10")
    implementation("org.jetbrains.kotlinx.kover:org.jetbrains.kotlinx.kover.gradle.plugin:0.9.9")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.10.4")
    implementation(libs.android.gradle.plugin)
    implementation(libs.vanniktech.maven.publish.plugin)
}
