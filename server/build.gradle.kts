plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "net.markdrew.biblebowl"
version = "0.1.0"

application {
    mainClass.set("net.markdrew.biblebowl.server.ApplicationKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":shared-api"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)

    // ESV fetch (server-side only) — Ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Persistence (Postgres via Exposed)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.tests)
    testImplementation(kotlin("test"))
}
