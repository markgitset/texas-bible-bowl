package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.ChapterTextDto
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.server.esv.EsvUpstreamException

fun Route.bibleRoutes(esv: EsvPassageService?) {
    // Public: study material never requires sign-in (signing in only *adds* capabilities).
    // e.g. GET /bible/ACT/2  (book accepts the 3-letter code or a full/partial name)
    get("/bible/{book}/{chapter}") {
        if (esv == null || !esv.isConfigured) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                ApiError("esv_unconfigured", "ESV service is not configured (set ESV_API_TOKEN)"),
            )
            return@get
        }
        val book = Book.parse(call.parameters["book"], default = null)
            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_book", "Unknown book"))
        val chapter = call.parameters["chapter"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("bad_chapter", "Invalid chapter"))

        try {
            val cached = esv.chapterText(book.chapterRef(chapter))
            call.respond(
                ChapterTextDto(
                    bookCode = cached.bookCode,
                    chapter = cached.chapter,
                    canonical = cached.canonical,
                    text = cached.text,
                )
            )
        } catch (e: EsvUpstreamException) {
            call.respond(HttpStatusCode.BadGateway, ApiError("esv_upstream", e.message ?: "ESV API error"))
        }
    }
}
