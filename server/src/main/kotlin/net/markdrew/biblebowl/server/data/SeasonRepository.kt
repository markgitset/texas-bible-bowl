package net.markdrew.biblebowl.server.data

import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.SeasonDto
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.atomic.AtomicReference

/**
 * The season the site and app are currently running (2026–27: Acts). Seeded on first boot and as
 * the no-database fallback, then edited in-app by SEASON_MANAGE holders. Values mirror the Hugo
 * site's `[params]` at migration time.
 */
val DEFAULT_SEASON = SeasonDto(
    eventYear = 2027,
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

interface SeasonRepository {
    fun current(): SeasonDto
    /** The season stored for [eventYear] (current or history row); null when never recorded. */
    fun byYear(eventYear: String): SeasonDto?
    fun update(season: SeasonDto): SeasonDto
}

class InMemorySeasonRepository(initial: SeasonDto = DEFAULT_SEASON) : SeasonRepository {
    private val ref = AtomicReference(initial)
    override fun current(): SeasonDto = ref.get()
    override fun byYear(eventYear: String): SeasonDto? = current().takeIf { it.eventYear.toString() == eventYear }
    override fun update(season: SeasonDto): SeasonDto = season.also(ref::set)
}

/**
 * Season rows keyed by event year (INT since V2); exactly one row is current. The payload is the
 * SeasonDto JSON *minus sites* (decoded with unknown keys ignored and defaults applied, so the
 * field set can evolve without migrations); sites are [SeasonSitesTable] rows so other tables can
 * FK them. The row also carries the season-wide append-only tester-id counter.
 */
object SeasonsTable : Table("seasons") {
    val year = integer("year")
    val isCurrent = bool("is_current").default(false)
    /** Next tester id to hand out (season-wide sequence; never reused — PR #63). */
    val nextTesterId = integer("next_tester_id").default(1)
    val payload = text("payload")
    override val primaryKey = PrimaryKey(year)
}

class PostgresSeasonRepository(private val db: Database) : SeasonRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        transaction(db) {
            if (SeasonsTable.selectAll().where { SeasonsTable.isCurrent eq true }.count() == 0L) {
                // The default year may already have a stub row (ensureSeasonRow, created non-current
                // when data referenced it before any season was configured) — promote it if so.
                val year = DEFAULT_SEASON.eventYear
                val payloadJson = json.encodeToString(DEFAULT_SEASON.copy(sites = emptyList()))
                if (SeasonsTable.selectAll().where { SeasonsTable.year eq year }.any()) {
                    SeasonsTable.update({ SeasonsTable.year eq year }) {
                        it[isCurrent] = true
                        it[payload] = payloadJson
                    }
                } else {
                    SeasonsTable.insert {
                        it[SeasonsTable.year] = year
                        it[isCurrent] = true
                        it[payload] = payloadJson
                    }
                }
            }
        }
    }

    private fun sitesFor(year: Int): List<EventSiteDto> =
        SeasonSitesTable.selectAll()
            .where { SeasonSitesTable.seasonYear eq year }
            .orderBy(SeasonSitesTable.sortOrder)
            .map { EventSiteDto(it[SeasonSitesTable.id], it[SeasonSitesTable.name], it[SeasonSitesTable.address]) }

    private fun decode(year: Int, payload: String): SeasonDto =
        json.decodeFromString<SeasonDto>(payload).copy(eventYear = year, sites = sitesFor(year))

    override fun current(): SeasonDto = transaction(db) {
        SeasonsTable.selectAll().where { SeasonsTable.isCurrent eq true }
            .firstOrNull()
            ?.let { decode(it[SeasonsTable.year], it[SeasonsTable.payload]) }
            ?: DEFAULT_SEASON
    }

    override fun byYear(eventYear: String): SeasonDto? = transaction(db) {
        val year = eventYear.toIntOrNull() ?: return@transaction null
        SeasonsTable.selectAll().where { SeasonsTable.year eq year }
            .firstOrNull()
            ?.let { decode(year, it[SeasonsTable.payload]) }
    }

    override fun update(season: SeasonDto): SeasonDto = transaction(db) {
        val year = season.eventYear
        // The updated season becomes the single current one (same year = edit; new year = rollover).
        SeasonsTable.update({ SeasonsTable.isCurrent eq true }) { it[isCurrent] = false }
        // Sites are stored as rows (FK targets), everything else as payload JSON without them.
        val payloadJson = json.encodeToString(season.copy(sites = emptyList()))
        val exists = SeasonsTable.selectAll().where { SeasonsTable.year eq year }.any()
        if (exists) {
            // In-place update preserves the season's append-only tester-id counter.
            SeasonsTable.update({ SeasonsTable.year eq year }) {
                it[isCurrent] = true
                it[payload] = payloadJson
            }
        } else {
            SeasonsTable.insert {
                it[SeasonsTable.year] = year
                it[isCurrent] = true
                it[payload] = payloadJson
            }
        }
        syncSites(year, season.sites)
        decode(year, payloadJson)
    }

    /**
     * Upserts the season's site rows to match [sites] (order becomes sortOrder). A removed site
     * that registrations still point at is released (their siteId goes null = "not chosen", the
     * pre-V2 semantics for a stale site id); one still referenced by cabins or tribes blocks the
     * update with an error, since those must always be site-pinned.
     */
    private fun syncSites(year: Int, sites: List<EventSiteDto>) {
        val keep = sites.map { it.id }.toSet()
        val existing = SeasonSitesTable.selectAll()
            .where { SeasonSitesTable.seasonYear eq year }
            .map { it[SeasonSitesTable.id] }
            .toSet()
        (existing - keep).forEach { removedId ->
            val pinned = CabinsTable.selectAll().where { CabinsTable.siteId eq removedId }.count() +
                TribesTable.selectAll().where { TribesTable.siteId eq removedId }.count()
            require(pinned == 0L) {
                "Site $removedId still has cabins or tribes pinned to it — move or delete them first"
            }
            RegistrationsTable.update({ RegistrationsTable.siteId eq removedId }) { it[siteId] = null }
            SeasonSitesTable.deleteWhere { SeasonSitesTable.id eq removedId }
        }
        sites.forEachIndexed { index, site ->
            if (site.id in existing) {
                SeasonSitesTable.update({ SeasonSitesTable.id eq site.id }) {
                    it[name] = site.name
                    it[address] = site.address
                    it[sortOrder] = index
                }
            } else {
                SeasonSitesTable.insert {
                    it[id] = site.id
                    it[seasonYear] = year
                    it[name] = site.name
                    it[address] = site.address
                    it[sortOrder] = index
                }
            }
        }
    }
}
