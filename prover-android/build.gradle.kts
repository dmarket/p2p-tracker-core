// The native TLSN prover for Android: the C ABI shipped in the pinned @dmarket/steam-provenance npm package
// (native/android/), checked against the package's BUILD.json and packaged byte for byte. A plain Android
// library rather than part of :core's androidMain because the Kotlin Multiplatform Android library plugin
// (AGP 9.3.1) has no externalNativeBuild and no ndkVersion. :core's androidMain depends on it, so the app
// gets it transitively; the jvm and js targets never see it, and building them needs no NDK.
plugins {
    id("dmarket.android.library")
}

android {
    namespace = "com.dmarket.p2p.tracker.prover"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    packaging {
        // The package's .so ships exactly as BUILD.json hashes it: never stripped on the way into the AAR.
        jniLibs.keepDebugSymbols += "**/libprovenance_client_cffi.so"
    }
}

// The ABIs the package ships and this AAR carries.
val proverAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// Reads the package :core's Kotlin/JS npm install put under the root build, after verifyTlsnProver has checked
// its integrity, version and commit.
val verifyProverNativeLibs = tasks.register<VerifyProverNativeLibs>("verifyProverNativeLibs") {
    group = "verification"
    description = "Verifies native/android of the pinned TLSN prover npm package against its BUILD.json."
    dependsOn(":core:verifyTlsnProver")
    packageVersion.set(TlsnProverPin.VERSION)
    abis.set(proverAbis)
    packageDir.set(rootProject.layout.buildDirectory.dir("js/node_modules/${TlsnProverPin.PACKAGE}"))
    committedNotices.set(rootProject.layout.projectDirectory.dir("licenses/steam-provenance-android"))
    jniLibsDir.set(layout.buildDirectory.dir("generated/prover-native/jniLibs"))
    includeDir.set(layout.buildDirectory.dir("generated/prover-native/include"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(verifyProverNativeLibs, VerifyProverNativeLibs::jniLibsDir)
    }
}
