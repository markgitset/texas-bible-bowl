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
// checked in (plain Hugo builds need no Gradle step) and kept fresh automatically: any build
// that compiles this module's JVM target regenerates it (finalizedBy below; up-to-date-checked,
// so a no-op when the enum hasn't changed), and deploy-web.sh regenerates it before every Hugo
// build. StudySectionsDataTest still fails CI while the *committed* copy is stale — mustRunAfter
// keeps that honest by making the test read the checked-in file before regeneration can touch it.
val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
val generateStudySectionsData by tasks.registering(JavaExec::class) {
    description = "Regenerates site/data/study-sections.json from the StudySection enum"
    classpath(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    mainClass.set("net.markdrew.biblebowl.api.StudySectionsDataKt")
    val target = rootProject.layout.projectDirectory.file("site/data/study-sections.json")
    args(target.asFile.absolutePath)
    inputs.files(jvmMainCompilation.output.allOutputs)
    outputs.file(target)
    mustRunAfter(tasks.named("jvmTest"))
}
tasks.named("compileKotlinJvm") { finalizedBy(generateStudySectionsData) }
