package net.markdrew.biblebowl.server.data

import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Small helpers shared by the Postgres repositories that touch the person-centric tables.
 * All of them assume they run inside an open Exposed transaction.
 */

/** A claim code no person holds yet (the unique index backstops the collision race). */
internal fun freshPersonClaimCode(): String {
    repeat(5) {
        val code = ClaimCodes.generate()
        if (PeopleTable.selectAll().where { PeopleTable.claimCode eq code }.none()) return code
    }
    error("Could not allocate a unique claim code")
}

/**
 * Guarantees a `seasons` row exists for [year] so season-FK inserts (registrations, cabins,
 * tribes, score releases…) can't fail on a season nobody has configured yet. The stub payload
 * carries the required SeasonDto fields; a later Season-settings edit replaces it wholesale.
 */
internal fun ensureSeasonRow(year: Int) {
    if (SeasonsTable.selectAll().where { SeasonsTable.year eq year }.any()) return
    SeasonsTable.insert {
        it[SeasonsTable.year] = year
        it[isCurrent] = false
        it[payload] = """{"eventYear":$year,"eventDateRange":"TBD","eventTheme":"TBD",""" +
            """"eventScripture":"TBD","bookCode":"ACT","chapterCount":28,""" +
            """"scholarshipAmount":"TBD","scholarshipDeadline":"TBD"}"""
    }
}

/**
 * Resolves the site a season-scoped row should pin to: [requested] when it's one of the season's
 * sites, else the season's lone site (single-site seasons never surface the choice), else null
 * (multi-site season with no/invalid choice — the caller decides how to fail).
 */
internal fun resolveSeasonSite(year: Int, requested: String?): String? {
    val sites = SeasonSitesTable.selectAll()
        .where { SeasonSitesTable.seasonYear eq year }
        .orderBy(SeasonSitesTable.sortOrder)
        .map { it[SeasonSitesTable.id] }
    return when {
        requested != null && requested in sites -> requested
        sites.size == 1 -> sites.single()
        else -> null
    }
}

/**
 * Like [resolveSeasonSite] but for the always-site-pinned rows (cabins, tribes): guarantees a
 * concrete site. A season with no configured sites gets a synthetic "Main Site" created lazily
 * (the same fallback the V2 migration used), so single-site seasons never surface the site choice
 * yet still satisfy the NOT NULL FK.
 */
internal fun resolveOrCreateSeasonSite(year: Int, requested: String?): String {
    resolveSeasonSite(year, requested)?.let { return it }
    ensureSeasonRow(year)
    val syntheticId = "main-site-$year"
    if (SeasonSitesTable.selectAll().where { SeasonSitesTable.id eq syntheticId }.none()) {
        SeasonSitesTable.insert {
            it[id] = syntheticId
            it[seasonYear] = year
            it[name] = "Main Site"
            it[sortOrder] = 0
        }
    }
    // A requested-but-unknown id in a multi-site season still resolves to the season's first site.
    return resolveSeasonSite(year, requested) ?: syntheticId
}
