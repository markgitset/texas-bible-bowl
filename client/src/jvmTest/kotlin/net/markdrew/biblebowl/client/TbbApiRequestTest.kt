package net.markdrew.biblebowl.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.client.TbbApi
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the exact requests [TbbApi] puts on the wire — in particular that every customize
 * option reaches the query string. A tiny recording HTTP server stands in for the backend
 * (TbbApi builds its own engine, so a mock engine can't be injected).
 */
class TbbApiRequestTest {

    private val requests = mutableListOf<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            val body = "%PDF-1.7 fake".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val api = TbbApi("http://127.0.0.1:${server.address.port}")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun bibleTextPdfSendsEveryCustomizeOption() = runBlocking {
        api.bibleTextPdf(
            fontSize = 14,
            twoColumns = true,
            justified = true,
            chapterBreaksPage = true,
            highlight = false,
            underlineUniqueWords = true,
        )
        val uri = requests.single()
        assertTrue(uri.startsWith("/generate/bible-text.pdf?"), uri)
        listOf(
            "fontSize=14", "twoColumns=true", "justified=true",
            "chapterBreaksPage=true", "highlight=false", "underlineUniqueWords=true",
        ).forEach { param -> assertTrue(param in uri, "expected $param in $uri") }
    }

    @Test
    fun bibleTextPdfDefaultsSendNoOptions() = runBlocking {
        api.bibleTextPdf()
        assertEquals("/generate/bible-text.pdf", requests.single(), "defaults are the server's defaults")
    }

    @Test
    fun practiceTestPdfSendsLimitAndSeed() = runBlocking {
        api.practiceTestPdf(Round.FIND_THE_VERSE, chapter = 7, limit = 20, seed = 1234)
        val uri = requests.single()
        listOf("round=FIND_THE_VERSE", "chapter=7", "limit=20", "seed=1234").forEach { param ->
            assertTrue(param in uri, "expected $param in $uri")
        }
    }
}
