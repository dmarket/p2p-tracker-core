// The headless seller client: the tracker loop on Node.js, with no browser and no UI. A trader runs one
// process per Steam account next to their bot; the bot pushes it the DMarket Trading API key and a Steam
// access token over a local management API, and nothing secret is written to disk.
//
//   commonMain — the product shell over :core's headless tracker: config, health, the log and the management
//                API's routing. The tracker itself (the loop, the pushed secrets, request signing) is :core's.
//                Unit-tested on the JVM and on Node (commonTest).
//   jsMain     — the Node actuals: file-backed storage, the HTTP listener and the process entry point.
//
// Not published yet: packaging (the Node single-executable build, the container image) comes with the
// distribution work.

plugins {
    id("dmarket.kmp.library")
}

kotlin {
    js {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // The platform hands the tracker an optional Ktor engine (a mock one in tests); :core keeps Ktor as
            // an `implementation` dependency, so it is declared here for that type to resolve.
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}
