import org.jetbrains.kotlin.gradle.dsl.JsModuleKind

plugins {
    id("dmarket.kmp.library")
}

kotlin {
    js {
        // The npm consumable for the web extension: an ES module library with TypeScript types.
        // Override the default output name (rootProject.name + module = "dmarket-p2p-tracker-core-core")
        // so the published entry point is a clean "p2p-tracker-core.mjs" / ".d.mts". Only :core is
        // renamed; the :domain chunk keeps its generated name and is imported internally, so consumers
        // still import only this single root module.
        outputModuleName = "p2p-tracker-core"
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jsMain.dependencies {
            implementation(libs.ktor.client.js)
            // No build-time dependency on the TLSN prover: it is a vendored runtime artifact, loaded by
            // relative import() at proof time. See the copyTlsnProver task below.
        }
    }
}

// The library output uses ES modules (useEsModules() above), but the Mocha / Node test runner
// needs CommonJS so that require() and __dirname are available in jsTest/Fixtures.kt.
//
// useEsModules() does two things for each compilation:
//   1. Sets compilation.compilerOptions.options.moduleKind = MODULE_ES
//      → this drives KotlinJsCompilation.fileExtension → determines the expected ".mjs" / ".js"
//        suffix in JsIrBinary.mainFileSyncPath (what jsNodeTest.onlyIf checks for existence).
//   2. Sets each binary's linkTask.compilerOptions.moduleKind = MODULE_ES
//      → this drives the actual file extension the Kotlin compiler writes out.
//
// We need to override BOTH for the test compilation so that the expected path and the produced
// file both use ".js" (CJS).  Two separate hooks are needed because (1) and (2) live on
// different objects.
kotlin {
    js {
        // Override (1): test compilation's own compilerOptions.  The suppression is intentional —
        // the deprecated accessor is the only one that drives KotlinJsCompilation.fileExtension.
        compilations.named("test") {
            @Suppress("DEPRECATION")
            compilerOptions.options.moduleKind.set(JsModuleKind.MODULE_COMMONJS)
        }
    }
}

// Override (2): the link task that produces the final test bundle.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink>()
    .matching { it.name == "compileTestDevelopmentExecutableKotlinJs" }
    .configureEach {
        compilerOptions {
            moduleKind.set(JsModuleKind.MODULE_COMMONJS)
        }
    }

// The vendored TLSN prover travels WITH the library: notary/WasmProverModule.kt import()s
// `./pkg/client_wasm.js` and `./transport/dist/index.js` relative to the emitted .mjs, so those two
// directories must sit beside it in the distribution — and therefore in the published npm tarball,
// which ships the whole productionLibrary dir. Without this task the notary path resolves to nothing.
//
// VERSION rides along so a shipped build can be traced back to the upstream prover it carries.
// SHA256SUMS deliberately does NOT: it also covers the artifact's own README.md, which is not copied
// (it would collide with the package readme), so a `-c` run here would report a spurious miss. The
// audit anchor is `vendor/tlsn/` in the source tree — see vendor/tlsn/INTEGRATION.md.
val copyTlsnProver by tasks.registering(Copy::class) {
    group = "build"
    description = "Copies the vendored TLSN prover (pkg/ + transport/) into the JS library distribution."
    val proverDir = rootProject.layout.projectDirectory.dir("vendor/tlsn")
    // Gradle treats a missing `from` directory as an empty tree, so without this the task would succeed,
    // copy nothing, and publish a tarball whose notary path 404s on the first proof — in production, on
    // the one code path this exists to enable. Fail at build time instead.
    doFirst {
        require(proverDir.file("pkg/client_wasm_bg.wasm").asFile.isFile) {
            "vendor/tlsn/pkg/client_wasm_bg.wasm is missing — the TLSN prover is not vendored. " +
                "See vendor/tlsn/INTEGRATION.md."
        }
    }
    from(proverDir) {
        include("pkg/**", "transport/**", "VERSION")
    }
    into(layout.buildDirectory.dir("dist/js/productionLibrary"))
}

tasks.named("jsBrowserProductionLibraryDistribution") { finalizedBy(copyTlsnProver) }
