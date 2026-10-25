import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    jvm("desktop")

    androidTarget {
        // Keep the Android Kotlin bytecode target aligned with the Java (BuildConfig) compile below.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // iosX64/iosArm64/iosSimulatorArm64 targets add here once a macOS host is
    // available — commonMain UI needs no changes. (The web app is :web, plain Kotlin/JS.)

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":shared-api"))
            implementation(project(":generation"))
            implementation(project(":client"))

            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)

            implementation(libs.navigation.compose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.core)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            implementation(kotlin("test"))
            // runComposeUiTest: drives the real NavController offscreen (NavigationTest).
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

android {
    namespace = "net.markdrew.biblebowl.app"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "net.markdrew.biblebowl"
        minSdk = 26
        targetSdk = 35
        // Play rejects an upload whose versionCode doesn't exceed the last one, so the
        // android-release workflow passes main's commit count: it grows with every commit and,
        // unlike a run number, survives renaming the workflow. Local builds are 1.
        versionCode = providers.environmentVariable("TBB_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = "0.1.0"
        // The APK talks to the live backend by default so a sideloaded build just works. Override for
        // local dev, e.g. `-Ptbb.backendUrl=http://10.0.2.2:8080` (the emulator's alias for the host).
        // The old texas-bible-bowl.fly.dev URL still works, so APKs built before the 2026-08 domain
        // migration keep running — but new builds use the branded host.
        val backendUrl = (project.findProperty("tbb.backendUrl") as String?) ?: "https://api.texasbiblebowl.org"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }
    // Release signing comes from the environment so the key never touches the repo: the
    // android-release workflow decodes its keystore secret to a temp file and points
    // TBB_KEYSTORE_FILE at it. Without that (local builds, CI) the release APK comes out unsigned.
    // One fixed key is what lets each new APK install as an update over the last; CI's debug APKs
    // are signed with a throwaway key per runner, so they can't.
    val releaseKeystore = providers.environmentVariable("TBB_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = providers.environmentVariable("TBB_KEYSTORE_PASSWORD").get()
            keyAlias = "tbb-release"
            keyPassword = storePassword // a PKCS12 keystore has one password for store and key
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // enable + configure proguard before a real release
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
