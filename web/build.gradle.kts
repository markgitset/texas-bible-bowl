plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization) // NavMenu is cached as JSON in localStorage
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "tbb.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":client"))
            implementation(project(":generation"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The dist must be self-contained: pull the site's shared stylesheet, logo, and favicon in at
// build time so the design system stays single-sourced under site/.
tasks.named<Copy>("jsProcessResources") {
    from(rootProject.file("site/static/css/custom.css")) { into("css") }
    from(rootProject.file("site/static/images/tbb-logo-white.png")) { into("images") }
    from(rootProject.file("site/static/favicon.ico"))
}
