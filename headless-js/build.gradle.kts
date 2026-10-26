import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject

// The JS facade of the headless tracker: a separate ES-module library, published as its own npm package, that
// puts :core's headless runtime in front of a Node host. The host hands it options and secrets through the
// facade's functions and gets back plain objects and JSON strings; no type that holds a secret is exported.
// It depends on :core and :domain and never the other way round, so the web extension's package, which is
// built from :core alone, carries none of it.

plugins {
    id("dmarket.kmp.library")
}

kotlin {
    js {
        // The entry point is p2p-tracker-headless.mjs / .d.mts rather than the generated
        // "dmarket-p2p-tracker-core-headless-js".
        outputModuleName = "p2p-tracker-headless"
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
            // The test-only factory takes a Ktor engine; :core keeps Ktor as an `implementation` dependency, so it
            // is declared here for that type to resolve.
            implementation(libs.ktor.client.core)
            // The prover processes' IPC messages.
            implementation(libs.kotlinx.serialization.json)
        }
        jsMain.dependencies {
            // The notary socket of a prover process: sends the signed upgrade headers and names a refused upgrade's
            // status. The version Ktor's JS client already brings, so the lock does not move.
            implementation(npm("ws", "8.22.0"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// What the package carries beside the compiled entry and chunks, at its root:
// - the TLSN prover the extension's package ships, pinned once (TlsnProverPin) and checked by :core's
//   verifyTlsnProver before anything copies it: pkg/ and transport/, where a prover process finds them unless
//   `prover.assetsDir` says otherwise, and VERSION, which ties a build to the notary it must match;
// - prover-child.mjs, the script each prover process runs;
// - index.d.ts, the package's types: the generated .d.mts types every function `any` and declares the browser exports
//   of :core besides, so the package does not point at it.
// Both distributions write the same directory, so either one leaves a complete package behind.
val headlessPackageFiles = tasks.register<Copy>("headlessPackageFiles") {
    group = "build"
    description = "Copies the TLSN prover, the prover child script and the package types into the JS library distribution."
    dependsOn(":core:verifyTlsnProver")
    val npm = kotlin.js().compilations.getByName("main").npmProject
    from(npm.nodeModulesDir.map { it.dir(TlsnProverPin.PACKAGE) }) {
        include(TlsnProverPin.SHIPPED)
    }
    from(layout.projectDirectory.dir("npm")) {
        include("prover-child.mjs", "index.d.ts")
    }
    into(layout.buildDirectory.dir("dist/js/productionLibrary"))
}

listOf("jsNodeProductionLibraryDistribution", "jsBrowserProductionLibraryDistribution").forEach { name ->
    tasks.named(name) { finalizedBy(headlessPackageFiles) }
    headlessPackageFiles.configure { mustRunAfter(name) }
}

// The contract tests import the built package, so its own imports (`ws`) must resolve: a copy of it sits under the
// build's js directory, whose node_modules Node finds walking up, as an installed package finds its dependencies.
val headlessDistUnderTest = tasks.register<Sync>("headlessDistUnderTest") {
    // The whole package as it ships; the files above land in the same directory as the distribution's own.
    dependsOn(headlessPackageFiles)
    from(tasks.named("jsNodeProductionLibraryDistribution"))
    into(rootProject.layout.buildDirectory.dir("js/headless-dist-under-test"))
}

// The contract tests read the built package (its entry and its .d.mts) and their golden files by absolute path:
// the test bundle is an ES module, which has no __dirname to find them from.
tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("jsNodeTest") {
    inputs.files(headlessDistUnderTest).withPropertyName("headlessDistribution")
    environment("HEADLESS_DIST_DIR", headlessDistUnderTest.get().destinationDir.absolutePath)
    environment("HEADLESS_TEST_RESOURCES", layout.projectDirectory.dir("src/jsTest/resources").asFile.absolutePath)
}
