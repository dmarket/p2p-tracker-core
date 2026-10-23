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
            implementation(npm("ws", "8.20.1"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// The contract tests read the built package (its entry and its .d.mts) and their golden files by absolute path:
// the test bundle is an ES module, which has no __dirname to find them from.
tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("jsNodeTest") {
    val distribution = tasks.named("jsNodeProductionLibraryDistribution")
    inputs.files(distribution).withPropertyName("headlessDistribution")
    environment("HEADLESS_DIST_DIR", layout.buildDirectory.dir("dist/js/productionLibrary").get().asFile.absolutePath)
    environment("HEADLESS_TEST_RESOURCES", layout.projectDirectory.dir("src/jsTest/resources").asFile.absolutePath)
}
