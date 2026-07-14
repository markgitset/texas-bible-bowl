package net.markdrew.biblebowl.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.markdrew.biblebowl.client.ApiException
import net.markdrew.biblebowl.client.TbbApi
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that [TbbApi] surfaces the server's human-readable [net.markdrew.biblebowl.api.ApiError]
 * message on non-2xx responses — for JSON endpoints as well as byte endpoints — instead of letting
 * ktor choke on deserializing the error body (a raw JsonConvertException dump in the UI).
 */
class TbbApiErrorTest {

    private var status = 503
    private var body = """{"code":"esv_unconfigured","message":"ESV service is not configured (set ESV_API_TOKEN)"}"""
    private var contentType = "application/json"

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val api = TbbApi("http://127.0.0.1:${server.address.port}")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun jsonEndpointSurfacesApiErrorMessage() = runBlocking {
        val e = assertFailsWith<ApiException> { api.headings() }
        assertEquals("ESV service is not configured (set ESV_API_TOKEN)", e.message)
    }

    @Test
    fun byteEndpointSurfacesApiErrorMessage() = runBlocking {
        val e = assertFailsWith<ApiException> { api.bibleTextPdf() }
        assertEquals("ESV service is not configured (set ESV_API_TOKEN)", e.message)
    }

    @Test
    fun nonJsonErrorBodyFallsBackToRawText() = runBlocking {
        status = 500
        body = "Internal Server Error"
        contentType = "text/plain"
        val e = assertFailsWith<ApiException> { api.questions() }
        assertEquals("Internal Server Error", e.message)
    }
}
