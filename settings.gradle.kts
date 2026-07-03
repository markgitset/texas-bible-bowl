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
    }
}

// Shared Kotlin Multiplatform modules
include(":core")          // pure domain: VerseRef, StudyData, DisjointRanges
include(":shared-api")    // DTOs + Role/Permission model shared by clients and server
include(":generation")    // study-material generators + Typst markup builders (pure Kotlin)
include(":app")           // Compose Multiplatform UI (web/desktop now; android/ios later)
include(":server")        // Ktor + Postgres backend
