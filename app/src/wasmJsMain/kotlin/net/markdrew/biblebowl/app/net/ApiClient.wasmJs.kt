package net.markdrew.biblebowl.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = HttpClient(Js) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
    install(HttpTimeout) { requestTimeoutMillis = BACKEND_REQUEST_TIMEOUT_MS }
}

/**
 * Reads the backend URL from a `window.TBB_BACKEND_URL` global the host page may define (the GitHub Pages
 * publish step injects the Fly URL). Absent in local `wasmJsBrowserRun`, so it falls back to localhost.
 */
actual fun defaultBaseUrl(): String = js("window.TBB_BACKEND_URL || 'http://localhost:8080'")
