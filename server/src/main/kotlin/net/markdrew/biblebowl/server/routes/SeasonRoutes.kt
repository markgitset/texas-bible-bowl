package net.markdrew.biblebowl.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requirePermission

/**
 * Season parameters (docs/gui-redesign.md §3): the static site and the app both render from
 * `GET /seasons/current`, so an admin edit is live on both halves immediately (the site's baked
 * values only linger for visitors who beat the params.js fetch).
 */
fun Route.seasonRoutes(users: UserRepository, seasons: SeasonRepository) {
    // Public: the same values the site prints (dates, fees, book) — nothing sensitive.
    get("/seasons/current") {
        call.respond(seasons.current())
    }

    authenticate {
        // Admin edit; same-year payload updates in place, a new year becomes the current season
        // (the prior year's row remains as history).
        put("/seasons/current") {
            val user = currentUser(users) ?: return@put
            if (!requirePermission(user, Permission.SEASON_MANAGE)) return@put
            val season = call.receive<SeasonDto>()
            if (season.eventYear.isBlank() || season.chapterCount < 1) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_season", "eventYear is required and chapterCount must be positive"),
                )
            }
            call.respond(seasons.update(season))
        }
    }
}
