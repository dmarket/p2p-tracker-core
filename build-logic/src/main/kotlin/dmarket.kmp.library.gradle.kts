// Convention plugin shared by every module in this repo.
//
// Live targets this phase: jvm() (fast unit tests) and js(IR) (the web-extension consumer).
// Android + iOS targets are intentionally deferred — see the commented block below.
// Keeping them out now means the build needs neither the Android SDK nor Xcode.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    id("com.diffplug.spotless")
}

kotlin {
    // Compile/test on JDK 17 regardless of the JDK running Gradle (auto-provisioned via foojay).
    jvmToolchain(17)

    jvm()

    js(IR) {
        // Tests run on Node (no headless browser needed); the browser env exists so :core can
        // emit a browser library distribution for the extension.
        nodejs()
        browser()
    }

    // TODO: enable the Android target (AAR → Maven Central).
    //   androidTarget { publishLibraryVariants("release") }
    // TODO: enable iOS targets (XCFramework → SwiftPM). Requires full
    //   Xcode, so this can only be built/tested on a macOS CI runner.
    //   iosArm64(); iosSimulatorArm64(); iosX64()
}

// Browser tests would require a headless Chrome; the equivalent coverage runs on Node + JVM.
tasks.matching { it.name == "jsBrowserTest" }.configureEach { enabled = false }

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1")
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint("1.3.1")
    }
}
