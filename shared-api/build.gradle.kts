import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "net.markdrew.biblebowl.api"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}
