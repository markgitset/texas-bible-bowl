package net.markdrew.biblebowl.server.data

import java.time.Instant
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
 * by (roster entry, round) — roster entries hang off a per-season registration, so the entry id
 * alone scopes a score to its season. Validation (0..maxPoints, round/division eligibility)
 * happens in the routes; repositories just store cells.
 */
interface ScoreRepository {
    /** Entered points by entry id and round for [entryIds]; entries with no scores yet are absent. */
    fun forEntries(entryIds: Collection<String>): Map<String, Map<Round, Int>>

    /** Upserts one score cell, or deletes it when [points] is null (an emptied grid cell). */
    fun set(rosterEntryId: String, round: Round, points: Int?, enteredByUserId: String)

    /** ISO-8601 instant [seasonYear]'s scores were released, or null while unreleased. */
    fun releasedAt(seasonYear: String): String?

    /** Releases or retracts [seasonYear]'s scores; returns the new releasedAt (null after retract). */
    fun setReleased(seasonYear: String, releasedByUserId: String, released: Boolean): String?
}

class InMemoryScoreRepository : ScoreRepository {
    private val cells = ConcurrentHashMap<String, MutableMap<Round, Int>>()
    private val releases = ConcurrentHashMap<String, Long>()
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

    override fun releasedAt(seasonYear: String): String? =
        releases[seasonYear]?.let { Instant.ofEpochMilli(it).toString() }

    override fun setReleased(seasonYear: String, releasedByUserId: String, released: Boolean): String? {
        if (released) releases.putIfAbsent(seasonYear, System.currentTimeMillis())
        else releases.remove(seasonYear)
        return releasedAt(seasonYear)
    }
}

class PostgresScoreRepository(private val db: Database) : ScoreRepository {

    override fun forEntries(entryIds: Collection<String>): Map<String, Map<Round, Int>> = transaction(db) {
        if (entryIds.isEmpty()) return@transaction emptyMap()
        ScoresTable.selectAll()
            .where { ScoresTable.rosterEntryId inList entryIds }
            .groupBy({ it[ScoresTable.rosterEntryId] }) {
                Round.valueOf(it[ScoresTable.round]) to it[ScoresTable.points]
            }
            .mapValues { (_, pairs) -> pairs.toMap() }
    }

    override fun set(rosterEntryId: String, round: Round, points: Int?, enteredByUserId: String): Unit =
        transaction(db) {
            if (points == null) {
                ScoresTable.deleteWhere {
                    (ScoresTable.rosterEntryId eq rosterEntryId) and (ScoresTable.round eq round.name)
                }
                return@transaction
            }
            val updated = ScoresTable.update({
                (ScoresTable.rosterEntryId eq rosterEntryId) and (ScoresTable.round eq round.name)
            }) {
                it[ScoresTable.points] = points
                it[ScoresTable.enteredByUserId] = enteredByUserId
                it[enteredAtEpochMs] = System.currentTimeMillis()
            }
            if (updated == 0) {
                ScoresTable.insert {
                    it[ScoresTable.rosterEntryId] = rosterEntryId
                    it[ScoresTable.round] = round.name
                    it[ScoresTable.points] = points
                    it[ScoresTable.enteredByUserId] = enteredByUserId
                    it[enteredAtEpochMs] = System.currentTimeMillis()
                }
            }
        }

    override fun releasedAt(seasonYear: String): String? = transaction(db) {
        ScoreReleasesTable.selectAll()
            .where { ScoreReleasesTable.seasonYear eq seasonYear }
            .singleOrNull()
            ?.let { Instant.ofEpochMilli(it[ScoreReleasesTable.releasedAtEpochMs]).toString() }
    }

    override fun setReleased(seasonYear: String, releasedByUserId: String, released: Boolean): String? =
        transaction(db) {
            if (!released) {
                ScoreReleasesTable.deleteWhere { ScoreReleasesTable.seasonYear eq seasonYear }
                return@transaction null
            }
            val existing = ScoreReleasesTable.selectAll()
                .where { ScoreReleasesTable.seasonYear eq seasonYear }
                .singleOrNull()
            val releasedMs = existing?.get(ScoreReleasesTable.releasedAtEpochMs) ?: run {
                val now = System.currentTimeMillis()
                ScoreReleasesTable.insert {
                    it[ScoreReleasesTable.seasonYear] = seasonYear
                    it[releasedAtEpochMs] = now
                    it[ScoreReleasesTable.releasedByUserId] = releasedByUserId
                }
                now
            }
            Instant.ofEpochMilli(releasedMs).toString()
        }
}
