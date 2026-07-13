package net.markdrew.biblebowl.server.data

import kotlinx.serialization.json.Json
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

interface SeasonRepository {
    fun current(): SeasonDto
    fun update(season: SeasonDto): SeasonDto
}

class InMemorySeasonRepository(initial: SeasonDto = DEFAULT_SEASON) : SeasonRepository {
    private val ref = AtomicReference(initial)
    override fun current(): SeasonDto = ref.get()
    override fun update(season: SeasonDto): SeasonDto = season.also(ref::set)
}

/**
 * Season rows keyed by event year; exactly one row is current. The payload is stored as JSON so
 * the (display-string-heavy) field set can evolve without migrations; prior seasons remain as
 * history rows for the future "start next season" admin flow.
 */
object SeasonsTable : Table("seasons") {
    val eventYear = varchar("event_year", 8)
    val isCurrent = bool("is_current").default(false)
    val payload = text("payload")
    override val primaryKey = PrimaryKey(eventYear)
}

class PostgresSeasonRepository(private val db: Database) : SeasonRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        transaction(db) {
            if (SeasonsTable.selectAll().where { SeasonsTable.isCurrent eq true }.count() == 0L) {
                SeasonsTable.insert {
                    it[eventYear] = DEFAULT_SEASON.eventYear
                    it[isCurrent] = true
                    it[payload] = json.encodeToString(DEFAULT_SEASON)
                }
            }
        }
    }

    override fun current(): SeasonDto = transaction(db) {
        SeasonsTable.selectAll().where { SeasonsTable.isCurrent eq true }
            .firstOrNull()
            ?.let { json.decodeFromString<SeasonDto>(it[SeasonsTable.payload]) }
            ?: DEFAULT_SEASON
    }

    override fun update(season: SeasonDto): SeasonDto = transaction(db) {
        // The updated season becomes the single current one (same year = edit; new year = rollover).
        SeasonsTable.update({ SeasonsTable.isCurrent eq true }) { it[isCurrent] = false }
        SeasonsTable.deleteWhere { SeasonsTable.eventYear eq season.eventYear }
        SeasonsTable.insert {
            it[eventYear] = season.eventYear
            it[isCurrent] = true
            it[payload] = json.encodeToString(season)
        }
        season
    }
}
