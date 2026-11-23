// Convention plugin for a published plain Android library (:prover-android): AGP's com.android.library with
// its built-in Kotlin, Maven publishing from gradle.properties like `dmarket.kmp.published`, and the same
// ktlint rules. The Android plugin ids resolve only here, where build-logic has AGP on its classpath.
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    id("com.diffplug.spotless")
}

android {
    // Same as `dmarket.kmp.published`: 37 for okhttp-android 5.5.x, 24 like the app.
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

mavenPublishing {
    configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.3.1")
    }
}
