package net.markdrew.biblebowl.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
}

/** Desktop points at a locally-running backend; override the TBB_BACKEND_URL env var to target a deploy. */
actual fun defaultBaseUrl(): String =
    System.getenv("TBB_BACKEND_URL") ?: TbbApi.DEFAULT_BASE_URL
