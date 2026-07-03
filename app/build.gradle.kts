import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    jvm("desktop")

    androidTarget()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("tbb")
        browser {
            commonWebpackConfig {
                outputFileName = "tbb.js"
            }
        }
        binaries.executable()
    }
    // iosX64/iosArm64/iosSimulatorArm64 targets add here once a macOS host is
    // available — commonMain UI needs no changes.

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":shared-api"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)

            // Shared networking to the Ktor backend (engine chosen per platform below).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.core)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
        }

        val wasmJsMain by getting
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.cio)
        }
    }
}

android {
    namespace = "net.markdrew.biblebowl.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "net.markdrew.biblebowl"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false // enable + configure proguard before a real release
        }
    }
}

compose.desktop {
    application {
        mainClass = "net.markdrew.biblebowl.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TexasBibleBowl"
            packageVersion = "1.0.0"
        }
    }
}
