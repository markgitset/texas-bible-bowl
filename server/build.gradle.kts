plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "net.markdrew.biblebowl"
version = "0.1.0"

application {
    mainClass.set("net.markdrew.biblebowl.server.ApplicationKt")
    // Baked into the installDist start script (bin/server), which is what the Docker image runs.
    applicationDefaultJvmArgs = listOf("-XX:MaxRAMPercentage=75")
}

// Deploy runs the application distribution (installDist → separate jars on the classpath), NOT the
// Ktor fat jar. Reason: the shadow fat jar merges every dependency's META-INF/services with a
// last-dependency-wins strategy that drops flyway-core's plugin registrations (its
// CoreResourceTypeProvider, which recognizes .sql files as migrations) — so at runtime Flyway
// "detects but rejects" V1/V2 and never migrates the schema. With separate jars each service file
// stays intact and Flyway sees all its plugins. (buildFatJar still works for local convenience.)

dependencies {
    implementation(project(":core"))
    implementation(project(":shared-api"))
    implementation(project(":generation"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.rate.limit)

    // ESV fetch (server-side only) — Ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Persistence (Postgres via Exposed)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.datetime)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.ktor.client.mock)
    testImplementation(kotlin("test"))
}
