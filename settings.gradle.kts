// Root settings for the DMarket P2P trade-tracker shared core.
// build-logic is an included build that supplies the `dmarket.kmp.library` convention plugin.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

// Auto-provision the JDK 17 toolchain used for compilation/tests (the only locally
// installed JDK is 25; this downloads a matching 17 on first build).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dmarket-p2p-tracker-core"

// :debug-harness is a dev-only module: the platform-free C1 conformance probes (commonMain, one per
// report/write request, unit-tested against MockEngine) plus a Chrome MV3 debug console that drives
// them and the live browser paths (scrape / refresh / vault / Steam + DMarket reads and writes). It is
// NEVER published (absent from the publish config — the npm artifact is built from :core alone) and
// nothing depends on it; it must not expand :core's audited @JsExport surface.
include(":domain", ":core", ":debug-harness")
