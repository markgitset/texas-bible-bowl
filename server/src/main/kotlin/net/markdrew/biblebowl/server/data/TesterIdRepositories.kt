package net.markdrew.biblebowl.server.data

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Assigned tester IDs (registration backlog item 13, F7): stable, append-only season-wide
 * sequential numbers, since V2 stored on [ParticipantsTable.testerId]. Which number a new tester
 * gets — the ordering — is the route's logic (see TesterRoutes); repositories just store
 * assignments and never mutate an existing one. The "rosterEntryId" is a participant id.
 */
interface TesterIdRepository {
    /** All assigned tester IDs for [seasonYear], by participant id. */
    fun forSeason(seasonYear: String): Map<String, Int>

    /**
     * Records [testerId] for [rosterEntryId] — assignments are permanent, so a participant that
     * already has a number keeps it (the new value is ignored). Callers keep (season, testerId)
     * unique; the season's `next_tester_id` counter is advanced past every number handed out so
     * it's never reused, even after the participation is deleted.
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
        val year = seasonYear.toIntOrNull() ?: return@transaction emptyMap()
        ParticipantsTable.selectAll()
            .where { (ParticipantsTable.seasonYear eq year) and ParticipantsTable.testerId.isNotNull() }
            .associate { it[ParticipantsTable.id] to it[ParticipantsTable.testerId]!! }
    }

    override fun assign(seasonYear: String, rosterEntryId: String, testerId: Int): Unit =
        transaction(db) {
            val year = seasonYear.toIntOrNull() ?: return@transaction
            val row = ParticipantsTable.selectAll()
                .where { ParticipantsTable.id eq rosterEntryId }
                .singleOrNull() ?: return@transaction
            if (row[ParticipantsTable.testerId] == null) {
                ParticipantsTable.update({ ParticipantsTable.id eq rosterEntryId }) {
                    it[ParticipantsTable.testerId] = testerId
                }
            }
            // Advance the never-reuse counter past this number.
            SeasonsTable.selectAll().where { SeasonsTable.year eq year }.singleOrNull()?.let { season ->
                if (season[SeasonsTable.nextTesterId] <= testerId) {
                    SeasonsTable.update({ SeasonsTable.year eq year }) {
                        it[nextTesterId] = testerId + 1
                    }
                }
            }
        }
}
