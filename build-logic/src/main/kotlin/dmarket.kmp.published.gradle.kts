// Convention plugin for the two modules that ship as Maven artifacts (:domain and :core): the Android
// target (an AAR per module) and Maven publishing. The other modules apply only `dmarket.kmp.library`
// and stay free of the Android SDK.
//
// Coordinates and the POM come from gradle.properties (GROUP, VERSION_NAME, POM_*; POM_ARTIFACT_ID in each
// module's own gradle.properties). Maven Central (Sonatype Central Portal) and signing are switched on there
// too (mavenCentralPublishing, signAllPublications); signing needs a key only for publishToMavenCentral, so
// publishToMavenLocal runs without one.
plugins {
    id("dmarket.kmp.library")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
}

kotlin {
    android {
        namespace = "com.dmarket.p2p.tracker.${project.name}"
        // 37 because okhttp-android 5.5.x (from ktor-client-okhttp 3.6.x) sets minCompileSdk=37.
        compileSdk = 37
        // Same as the Android app.
        minSdk = 24
        withHostTest {}
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }
    }
}
