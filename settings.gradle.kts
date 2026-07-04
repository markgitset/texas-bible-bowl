rootProject.name = "texas-bible-bowl"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle auto-provision a JDK 25 for the toolchain that runs :core:jvmTest and
// :server:test (chupacabra-core ships Java 25 bytecode). No-op when a JDK 25 is already
// installed and auto-detected (e.g. via SDKMAN locally).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

// Shared Kotlin Multiplatform modules
include(":core")          // pure domain: VerseRef, StudyData, DisjointRanges
include(":shared-api")    // DTOs + Role/Permission model shared by clients and server
include(":generation")    // study-material generators + Typst markup builders (pure Kotlin)
include(":app")           // Compose Multiplatform UI (web/desktop now; android/ios later)
include(":server")        // Ktor + Postgres backend
