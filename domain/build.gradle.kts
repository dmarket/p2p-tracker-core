plugins {
    id("dmarket.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The pure core is where the bulk of the logic lives, so it carries the coverage gate.
kover {
    reports {
        verify {
            rule {
                minBound(70)
            }
        }
    }
}
