// The native TLSN prover for Android: the C ABI shipped in the pinned @dmarket/steam-provenance npm package
// (native/android/), checked against the package's BUILD.json and packaged byte for byte. A plain Android
// library rather than part of :core's androidMain because the Kotlin Multiplatform Android library plugin
// (AGP 9.3.1) has no externalNativeBuild and no ndkVersion. :core's androidMain depends on it, so the app
// gets it transitively; the jvm and js targets never see it, and building them needs no NDK.
plugins {
    id("dmarket.android.library")
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

// The NDK and CMake the JNI layer builds with; the package's .so are built with the same NDK (its toolchain.txt).
val proverNdk = "30.0.16248370"
val proverCmake = "4.1.2"

android {
    namespace = "com.dmarket.p2p.tracker.prover"
    ndkVersion = proverNdk
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += proverAbis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DPROVENANCE_JNILIBS=${verifyProverNativeLibs.get().jniLibsDir.get().asFile}",
                    "-DPROVENANCE_INCLUDE=${verifyProverNativeLibs.get().includeDir.get().asFile}",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = proverCmake
        }
    }
    packaging {
        // The package's .so ships exactly as BUILD.json hashes it: never stripped on the way into the AAR.
        jniLibs.keepDebugSymbols += "**/libprovenance_client_cffi.so"
    }
}

// The CMake build reads the staged header and libraries.
tasks.named("preBuild") { dependsOn(verifyProverNativeLibs) }

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(verifyProverNativeLibs, VerifyProverNativeLibs::jniLibsDir)
    }
}

dependencies {
    // Device tests only (connectedAndroidTest): the bridge needs a device to load its .so.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
