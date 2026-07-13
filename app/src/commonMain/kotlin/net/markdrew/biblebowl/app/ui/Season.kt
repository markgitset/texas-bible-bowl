package net.markdrew.biblebowl.app.ui

import androidx.compose.runtime.compositionLocalOf
import net.markdrew.biblebowl.api.SeasonDto

/**
 * Baked fallback for the current season, mirroring the server's default: shown until (or if never)
 * `GET /seasons/current` answers, so the app renders sensibly offline and against old backends.
 */
val FALLBACK_SEASON = SeasonDto(
    eventYear = "2027",
    eventDateRange = "April 2–4",
    eventTheme = "TBD",
    eventScripture = "Acts",
    bookCode = "ACT",
    chapterCount = 28,
    scholarshipAmount = "$25,000",
    registrationOpens = "in February",
    registrationDeadline = "TBD",
    scholarshipDeadline = "TBD",
    priceAdult = "TBD (Was $85 in 2026)",
    priceChild = "TBD (Was $65 in 2026)",
    priceTshirt = "TBD (Was $10 in 2026)",
    tbbScholarshipAmount = "$1,000",
    maryOrbisonAmount = "$1,500",
    paulHendricksonAmount = "TBD",
)

/** The current season, provided at the app root and refreshed from the server on launch. */
val LocalSeason = compositionLocalOf { FALLBACK_SEASON }

/** The season label spanning two school years, e.g. "2026–27". */
val SeasonDto.schoolYear: String
    get() {
        val year = eventYear.toIntOrNull() ?: return eventYear
        val suffix = (year % 100).toString().padStart(2, '0')
        return "${year - 1}–$suffix"
    }
