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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
