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
    jniLibsDir.set(layout.buildDirectory.dir("generated/prover-native/jniLibs")) // read by CMake below too
    includeDir.set(layout.buildDirectory.dir("generated/prover-native/include"))
}

// The NDK and CMake the JNI layer builds with; the package's .so are built with the same NDK (its toolchain.txt).
val proverNdk = "30.0.16248370"
val proverCmake = "4.1.2"

// AGP fails the configuration of a project with externalNativeBuild when the NDK is missing, which would take
// every JVM and JS task of the build down with it. So the native build is set up only where both are installed,
// and requireProverNdk below fails every task that packages native libraries where they are not: an AAR
// without libprovenance_jni.so never builds green.
val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
val proverNdkInstalled = sdkDir.resolve("ndk/$proverNdk").isDirectory && sdkDir.resolve("cmake/$proverCmake").isDirectory

android {
    namespace = "com.dmarket.p2p.tracker.prover"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += proverAbis }
    }
    if (proverNdkInstalled) {
        ndkVersion = proverNdk
        defaultConfig.externalNativeBuild.cmake.arguments += listOf(
            "-DPROVENANCE_JNILIBS=${layout.buildDirectory.dir("generated/prover-native/jniLibs").get().asFile}",
            "-DPROVENANCE_INCLUDE=${layout.buildDirectory.dir("generated/prover-native/include").get().asFile}",
        )
        externalNativeBuild.cmake {
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

val requireProverNdk = tasks.register("requireProverNdk") {
    val installed = proverNdkInstalled
    val sdk = sdkDir
    doLast {
        if (!installed) {
            throw GradleException(
                "The native prover needs NDK $proverNdk and CMake $proverCmake in $sdk: " +
                    "sdkmanager \"ndk;$proverNdk\" \"cmake;$proverCmake\". See core/PROVER.md.",
            )
        }
    }
}
tasks.matching { Regex("merge\\w*NativeLibs").matches(it.name) }.configureEach { dependsOn(requireProverNdk) }

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
