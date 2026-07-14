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
include(":client")        // typed TbbApi backend client shared by the Compose apps and the web app
include(":app")           // Compose Multiplatform UI (android/desktop; ios later)
include(":web")           // Kotlin/JS DOM web app (replaces the wasm web target)
include(":server")        // Ktor + Postgres backend
