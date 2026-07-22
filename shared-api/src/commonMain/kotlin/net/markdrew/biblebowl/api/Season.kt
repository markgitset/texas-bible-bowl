package net.markdrew.biblebowl.api

/**
 * Baked fallback for the current season, mirroring the server's default: shown until (or if never)
 * `GET /seasons/current` answers, so clients render sensibly offline and against old backends.
 */
val FALLBACK_SEASON = SeasonDto(
    eventYear = "2027",
    eventDateRange = "April 2–4",
    eventTheme = "TBD",
    eventScripture = "Acts",
    studySet = "acts",
    bookCode = "ACT",
    chapterCount = 28,
    scholarshipAmount = "$25,000",
    registrationOpensOn = null,
    registrationClosesOn = null,
    scholarshipDeadline = "TBD",
    priceContestantCents = null,
    priceVolunteerCents = null,
    priceChildCents = null,
    priceTshirtCents = null,
    feesTentative = true,
    tbbScholarshipAmount = "$1,000",
    maryOrbisonAmount = "$1,500",
    paulHendricksonAmount = "TBD",
)

/** The season label spanning two school years, e.g. "2026–27". */
val SeasonDto.schoolYear: String
    get() {
        val year = eventYear.toIntOrNull() ?: return eventYear
        val suffix = (year % 100).toString().padStart(2, '0')
        return "${year - 1}–$suffix"
    }

/** Formats a fee in cents for display: "$85", "$12.50", or "TBD" when null. */
fun formatCents(cents: Int?): String {
    if (cents == null) return "TBD"
    val dollars = cents / 100
    val remainder = cents % 100
    return if (remainder == 0) "$$dollars" else "$$dollars.${remainder.toString().padStart(2, '0')}"
}

private val MONTH_NAMES = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** Formats an ISO-8601 date ("2027-02-01") for display: "February 1, 2027", or "TBD" when null. */
fun formatIsoDate(iso: String?): String {
    if (iso == null) return "TBD"
    val parts = iso.split('-')
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    if (parts.size != 3 || month == null || month !in 1..12 || day == null) return iso
    return "${MONTH_NAMES[month - 1]} $day, ${parts[0]}"
}

/** True when this season runs at two or more event sites, so registrations must pick one. */
val SeasonDto.multiSite: Boolean
    get() = sites.size > 1

/**
 * Resolves the event site a registration's [siteId] points at. A single-site season resolves to
 * its lone site no matter what (nothing to choose, so a null or stale id doesn't matter); a
 * multi-site season resolves only a valid current site id — null means "not chosen yet" (or the
 * chosen site was since removed), which blocks submit.
 */
fun SeasonDto.siteFor(siteId: String?): EventSiteDto? =
    sites.singleOrNull() ?: sites.firstOrNull { it.id == siteId }

/**
 * Canonical slug of an event-site name: "White River Youth Camp" → "white-river-youth-camp".
 * This is the same rule the workbook seed converter (tools/seed/convert_registration_xlsx.py,
 * `site_id()`) applies to the workbook's site names, and the season editor derives new
 * [EventSiteDto.id]s from it — so seeded siteIds and admin-created sites resolve to each other
 * by name without manual mapping.
 */
fun siteSlug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** A note to show near prices while [SeasonDto.feesTentative] is set; empty otherwise. */
val SeasonDto.feesNote: String
    get() = if (feesTentative) "Prices are tentative and subject to change." else ""
