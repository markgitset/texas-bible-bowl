package net.markdrew.biblebowl.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

// OkHttp engine: correctly honors Android's network-security-config (hostname-aware TLS).
actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(HttpTimeout) { requestTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS }
}

// The Android app bakes its backend URL into its own BuildConfig (invisible to this module) and passes
// it to TbbApi(baseUrl) explicitly in MainActivity; this default only covers bare TbbApi() construction.
actual fun defaultBaseUrl(): String = TbbApi.DEFAULT_BASE_URL
