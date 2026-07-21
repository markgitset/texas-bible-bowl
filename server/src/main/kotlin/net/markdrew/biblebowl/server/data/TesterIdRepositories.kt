package net.markdrew.biblebowl.server.data

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Assigned tester IDs (registration backlog item 13, F7): stable, append-only per-site sequential
 * numbers keyed by roster entry. Which number a new tester gets — the site blocks, the ordering —
 * is the route's logic (see TesterRoutes); repositories just store assignments and never mutate
 * an existing one.
 */
interface TesterIdRepository {
    /** All assigned tester IDs for [seasonYear], by roster entry id. */
    fun forSeason(seasonYear: String): Map<String, Int>

    /**
     * Records [testerId] for [rosterEntryId] — assignments are permanent, so an entry that already
     * has a number keeps it (the new value is ignored). Callers keep (season, testerId) unique.
     */
    fun assign(seasonYear: String, rosterEntryId: String, testerId: Int)
}

class InMemoryTesterIdRepository : TesterIdRepository {
    private val bySeason = ConcurrentHashMap<String, MutableMap<String, Int>>()

    override fun forSeason(seasonYear: String): Map<String, Int> =
        bySeason[seasonYear]?.toMap().orEmpty()

    override fun assign(seasonYear: String, rosterEntryId: String, testerId: Int) {
        bySeason.getOrPut(seasonYear) { ConcurrentHashMap() }.putIfAbsent(rosterEntryId, testerId)
    }
}

class PostgresTesterIdRepository(private val db: Database) : TesterIdRepository {

    override fun forSeason(seasonYear: String): Map<String, Int> = transaction(db) {
        TesterIdsTable.selectAll()
            .where { TesterIdsTable.seasonYear eq seasonYear }
            .associate { it[TesterIdsTable.rosterEntryId] to it[TesterIdsTable.testerId] }
    }

    override fun assign(seasonYear: String, rosterEntryId: String, testerId: Int): Unit =
        transaction(db) {
            val existing = TesterIdsTable.selectAll()
                .where {
                    (TesterIdsTable.seasonYear eq seasonYear) and
                        (TesterIdsTable.rosterEntryId eq rosterEntryId)
                }
                .any()
            if (!existing) {
                TesterIdsTable.insert {
                    it[TesterIdsTable.seasonYear] = seasonYear
                    it[TesterIdsTable.rosterEntryId] = rosterEntryId
                    it[TesterIdsTable.testerId] = testerId
                    it[assignedAtEpochMs] = System.currentTimeMillis()
                }
            }
        }
}
