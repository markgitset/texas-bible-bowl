import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.support.serviceOf
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
    // iosX64/iosArm64/iosSimulatorArm64 targets drop in here once a macOS host is
    // available — commonMain code needs no changes.

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // JVM-only: the copied bible-bowl generation/analysis code (server-side) uses kotlin-logging.
        jvmMain.dependencies {
            implementation(libs.kotlin.logging)
            // Range utilities (DisjointRangeMap/Set, etc.) — was vendored under
            // net.markdrew.chupacabra.core; now the published KMP library. `api` (not
            // `implementation`) because :server references these types directly, just as
            // it did when the classes were compiled into :core itself.
            api(libs.chupacabra.core)
        }
    }
}

android {
    namespace = "net.markdrew.biblebowl.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
}

// chupacabra-core's JVM classes are Java 25 bytecode, so the JVM tests that load them
// at runtime must execute on a JDK 25. Gradle itself keeps running on its launcher JDK
// (compilation is fine there); only this test task is routed to a JDK 25 toolchain.
tasks.named<Test>("jvmTest") {
    javaLauncher.set(
        serviceOf<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    )
}
