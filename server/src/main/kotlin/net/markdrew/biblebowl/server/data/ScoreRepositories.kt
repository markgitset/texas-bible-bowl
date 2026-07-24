package net.markdrew.biblebowl.server.data

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.markdrew.biblebowl.model.Round
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Entered round scores and the season release flag (docs/gui-redesign.md §5F). Scores are keyed
 * by (participant, round) — a participation hangs off a per-season registration, so the
 * participant id alone scopes a score to its season. Validation (0..maxPoints, round/division
 * eligibility) happens in the routes; repositories just store cells. The `rosterEntryId`
 * parameter names are kept for wire/route compatibility — the value is a participant id.
 */
interface ScoreRepository {
    /** Entered points by entry id and round for [entryIds]; entries with no scores yet are absent. */
    fun forEntries(entryIds: Collection<String>): Map<String, Map<Round, Int>>

    /** Upserts one score cell, or deletes it when [points] is null (an emptied grid cell). */
    fun set(rosterEntryId: String, round: Round, points: Int?, enteredByUserId: String)

    /**
     * Release stamps for [seasonYear] by site id → ISO-8601 instant. The key "" is the season-wide
     * release (also the only key a site-less season uses); a per-site release keys on the site id.
     * A site's *effective* release is its own stamp or, failing that, the season-wide one — see
     * [releasedAtFor].
     */
    fun releases(seasonYear: String): Map<String, String>

    /**
     * Releases or retracts one site's scores ([siteId] "" = season-wide); returns the new
     * releasedAt for that key (null after retract).
     */
    fun setReleased(seasonYear: String, siteId: String, releasedByUserId: String, released: Boolean): String?
}

/** A [siteId]'s effective release instant: its own stamp, else the season-wide ("") stamp, else null. */
fun Map<String, String>.releasedAtFor(siteId: String?): String? = this[siteId ?: ""] ?: this[""]

class InMemoryScoreRepository : ScoreRepository {
    private val cells = ConcurrentHashMap<String, MutableMap<Round, Int>>()
    // (seasonYear, siteId) -> releasedAt epoch ms; siteId "" is the season-wide release.
    private val releases = ConcurrentHashMap<Pair<String, String>, Long>()
    private val lock = Any()

    override fun forEntries(entryIds: Collection<String>): Map<String, Map<Round, Int>> = synchronized(lock) {
        entryIds.mapNotNull { id -> cells[id]?.let { id to it.toMap() } }.toMap()
    }

    override fun set(rosterEntryId: String, round: Round, points: Int?, enteredByUserId: String) {
        synchronized(lock) {
            if (points == null) {
                cells[rosterEntryId]?.remove(round)
            } else {
                cells.getOrPut(rosterEntryId) { mutableMapOf() }[round] = points
            }
        }
    }

    override fun releases(seasonYear: String): Map<String, String> =
        releases.filterKeys { it.first == seasonYear }
            .entries.associate { (key, ms) -> key.second to Instant.ofEpochMilli(ms).toString() }

    override fun setReleased(seasonYear: String, siteId: String, releasedByUserId: String, released: Boolean): String? {
        val key = seasonYear to siteId
        if (released) releases.putIfAbsent(key, System.currentTimeMillis())
        else releases.remove(key)
        return releases[key]?.let { Instant.ofEpochMilli(it).toString() }
    }
}

class PostgresScoreRepository(private val db: Database) : ScoreRepository {

    override fun forEntries(entryIds: Collection<String>): Map<String, Map<Round, Int>> = transaction(db) {
        if (entryIds.isEmpty()) return@transaction emptyMap()
        ScoresTable.selectAll()
            .where { ScoresTable.participantId inList entryIds }
            .groupBy({ it[ScoresTable.participantId] }) {
                Round.valueOf(it[ScoresTable.round]) to it[ScoresTable.points]
            }
            .mapValues { (_, pairs) -> pairs.toMap() }
    }

    override fun set(rosterEntryId: String, round: Round, points: Int?, enteredByUserId: String): Unit =
        transaction(db) {
            if (points == null) {
                ScoresTable.deleteWhere {
                    (ScoresTable.participantId eq rosterEntryId) and (ScoresTable.round eq round.name)
                }
                return@transaction
            }
            val updated = ScoresTable.update({
                (ScoresTable.participantId eq rosterEntryId) and (ScoresTable.round eq round.name)
            }) {
                it[ScoresTable.points] = points
                it[ScoresTable.enteredByUserId] = enteredByUserId
                it[enteredAtEpochMs] = System.currentTimeMillis()
            }
            if (updated == 0) {
                ScoresTable.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[ScoresTable.participantId] = rosterEntryId
                    it[ScoresTable.round] = round.name
                    it[ScoresTable.points] = points
                    it[ScoresTable.enteredByUserId] = enteredByUserId
                    it[enteredAtEpochMs] = System.currentTimeMillis()
                }
            }
        }

    override fun releases(seasonYear: String): Map<String, String> = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction emptyMap()
        ScoreReleasesTable.selectAll()
            .where { ScoreReleasesTable.seasonYear eq year }
            .associate { it[ScoreReleasesTable.siteId] to Instant.ofEpochMilli(it[ScoreReleasesTable.releasedAtEpochMs]).toString() }
    }

    override fun setReleased(seasonYear: String, siteId: String, releasedByUserId: String, released: Boolean): String? =
        transaction(db) {
            val year = seasonYear.toIntOrNull() ?: return@transaction null
            if (!released) {
                ScoreReleasesTable.deleteWhere {
                    (ScoreReleasesTable.seasonYear eq year) and (ScoreReleasesTable.siteId eq siteId)
                }
                return@transaction null
            }
            ensureSeasonRow(year)
            val existing = ScoreReleasesTable.selectAll()
                .where { (ScoreReleasesTable.seasonYear eq year) and (ScoreReleasesTable.siteId eq siteId) }
                .singleOrNull()
            val releasedMs = existing?.get(ScoreReleasesTable.releasedAtEpochMs) ?: run {
                val now = System.currentTimeMillis()
                ScoreReleasesTable.insert {
                    it[ScoreReleasesTable.seasonYear] = year
                    it[ScoreReleasesTable.siteId] = siteId
                    it[releasedAtEpochMs] = now
                    it[ScoreReleasesTable.releasedByUserId] = releasedByUserId
                }
                now
            }
            Instant.ofEpochMilli(releasedMs).toString()
        }
}
