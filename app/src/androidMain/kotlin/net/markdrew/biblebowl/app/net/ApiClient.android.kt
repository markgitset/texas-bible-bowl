package net.markdrew.biblebowl.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.app.BuildConfig

// OkHttp engine: correctly honors Android's network-security-config (hostname-aware TLS).
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(HttpTimeout) { requestTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS }
}

// Baked in at build time (BuildConfig.BACKEND_URL): the live Fly backend by default, overridable via the
// `tbb.backendUrl` Gradle property for local dev.
actual fun defaultBaseUrl(): String = BuildConfig.BACKEND_URL
