// Dev-only debug harness. Compiles a SEPARATE js ESM library (`@JsExport` DebugApi) that the
// Chrome MV3 debug extension under tools/debug-extension/ loads. It depends on :core + :domain and
// reuses their already-public actuals (scraper, refresher, vault, Ktor clients) so it never widens
// :core's audited surface. NOT published — there is no `maven-publish`/vanniktech block here, and
// the release workflow must never publish this module.
//
// The module is split by what needs a browser:
//   commonMain — the platform-free part (C1ReportProbes, SecretRedaction): takes ports, no chrome.*,
//                so it is unit-testable and IS unit-tested (commonTest below, on JVM + Node).
//   jsMain     — the @JsExport facade the extension loads, plus the live browser actuals.

plugins {
    id("dmarket.kmp.library")
}

kotlin {
    js {
        // An ES-module library with TypeScript types, same shape as :core's npm consumable.
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // :core keeps Ktor as `implementation` (not transitive), but the debug facade constructs
            // KtorSteamReadClient / KtorMarketplaceClient directly, so it needs ktor-client-core on
            // its own compile classpath to type-check those constructors.
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // MockEngine stands in for the backend: the probes' request bodies and response parsing are
            // asserted without a browser or a live gateway.
            implementation(libs.ktor.client.mock)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

// Build the dev distribution, then esbuild-bundle the entry .mjs into a SINGLE self-contained ESM
// file under the extension's vendor/ folder. Bundling (rather than copying the loose .mjs tree)
// resolves any bare npm specifiers pulled in by JS-target dependencies into the output, which
// neither a browser page nor — crucially — an MV3 service worker can resolve on its own (import
// maps don't work in workers). esbuild inlines them (resolved from the Kotlin-managed node_modules
// via NODE_PATH).
// Run:  ./gradlew :debug-harness:assembleDebugExtension
val assembleDebugExtension by tasks.registering(Exec::class) {
    group = "debug harness"
    description = "Builds + esbuild-bundles the debug ESM library into tools/debug-extension/vendor/."
    dependsOn("jsBrowserDevelopmentLibraryDistribution")

    val entry = layout.buildDirectory.file("dist/js/developmentLibrary/dmarket-p2p-tracker-core-debug-harness.mjs")
    val vendorDir = rootProject.layout.projectDirectory.dir("tools/debug-extension/vendor")
    val outFile = vendorDir.file("harness.bundle.mjs")
    // @js-joda/core (and every Kotlin npm package) lives under the root build's node_modules.
    val nodeModules = rootProject.layout.buildDirectory.dir("js/node_modules")

    doFirst {
        delete(vendorDir)
        mkdir(vendorDir)
    }

    environment("NODE_PATH", nodeModules.get().asFile.absolutePath)
    commandLine(
        "npx", "--yes", "esbuild@0.24.2",
        entry.get().asFile.absolutePath,
        "--bundle",
        "--format=esm",
        "--platform=browser",
        "--external:ws", // node-only websocket transport; not reachable from the debug surface
        // :core lazily import()s the vendored prover by relative path. Those files are copied into the
        // :core PRODUCTION distribution, not this harness one, and the harness hardcodes the no-op prover
        // so the imports never execute — leave them unresolved rather than failing the bundle. A real
        // client keeps `./pkg/` external for the same reason it must (import.meta.url) but DOES ship it.
        // These two prefixes mirror the specifiers in :core's WasmProverModule.kt; changing them there
        // without changing them here silently breaks this bundle.
        "--external:./pkg/*",
        "--external:./transport/*",
        "--log-level=warning",
        "--outfile=${outFile.asFile.absolutePath}",
    )
}
