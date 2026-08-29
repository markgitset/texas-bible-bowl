plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm()
    js {
        browser()
    }
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            // api, not implementation: @Serializable companions extend SerializerFactory,
            // so consumers need serialization-core on their compile classpath.
            api(libs.kotlinx.serialization.json)
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

// Bakes the StudySection enum into site/data/study-sections.json, which the Hugo navbar
// (nav.html) and Site Map (sitemap.html) render the Study & Practice links from. The file is
// checked in (plain Hugo builds need no Gradle step); StudySectionsDataTest fails when it's stale.
val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
tasks.register<JavaExec>("generateStudySectionsData") {
    description = "Regenerates site/data/study-sections.json from the StudySection enum"
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("net.markdrew.biblebowl.api.StudySectionsDataKt")
    args(rootProject.layout.projectDirectory.file("site/data/study-sections.json").asFile.absolutePath)
}
