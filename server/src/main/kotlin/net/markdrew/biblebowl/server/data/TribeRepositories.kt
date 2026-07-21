package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.TribeDto
import net.markdrew.biblebowl.api.TribeLeaderDto
import net.markdrew.biblebowl.api.UpsertTribeRequest
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Outcome of adding/renaming a tribe: the per-(season, site) name uniqueness is reported
 * distinctly so the route can explain the collision (mirrors [CabinResult]).
 */
sealed interface TribeResult {
    data class Ok(val tribe: TribeDto) : TribeResult
    data object NotFound : TribeResult
    /** Another tribe at the same season + site already has this name (case-insensitively). */
    data object NameTaken : TribeResult
}

/**
 * Tribes & tribe leaders (item 16, F10): per-season tribes and their assigned leaders — free-form
 * adult names, like housing's check-out duties. Deliberately thin: the willing-leader pool (item
 * 8's `tribeLeaderWilling` flags) is derived by clients from the registration desk, never stored.
 */
interface TribeRepository {
    /** Every tribe for [seasonYear] with its leaders, sorted by site then name. */
    fun listTribes(seasonYear: String): List<TribeDto>
    fun addTribe(seasonYear: String, req: UpsertTribeRequest): TribeResult
    fun updateTribe(tribeId: String, req: UpsertTribeRequest): TribeResult
    /** Deletes a tribe and its leader rows. */
    fun deleteTribe(tribeId: String): Boolean
    /** Assigns a leader; null when the tribe doesn't exist. */
    fun addLeader(tribeId: String, name: String): TribeLeaderDto?
    fun deleteLeader(leaderId: String): Boolean
}

private val TRIBE_ORDER = compareBy<TribeDto>({ it.siteId ?: "" }, { it.name.lowercase() })

// ---------------------------------------------------------------------------
// In-memory implementation (no DATABASE_URL: local dev & tests)
// ---------------------------------------------------------------------------

class InMemoryTribeRepository : TribeRepository {
    private data class Tribe(val seasonYear: String, val dto: TribeDto)

    private val tribes = ConcurrentHashMap<String, Tribe>()

    override fun listTribes(seasonYear: String): List<TribeDto> =
        tribes.values.filter { it.seasonYear == seasonYear }.map { it.dto }.sortedWith(TRIBE_ORDER)

    private fun nameTaken(seasonYear: String, siteId: String?, name: String, exceptId: String?): Boolean =
        tribes.values.any {
            it.seasonYear == seasonYear && it.dto.siteId == siteId && it.dto.id != exceptId &&
                it.dto.name.equals(name, ignoreCase = true)
        }

    override fun addTribe(seasonYear: String, req: UpsertTribeRequest): TribeResult {
        val name = req.name.trim()
        if (nameTaken(seasonYear, req.siteId, name, exceptId = null)) return TribeResult.NameTaken
        val dto = TribeDto(UUID.randomUUID().toString(), name, req.siteId)
        tribes[dto.id] = Tribe(seasonYear, dto)
        return TribeResult.Ok(dto)
    }

    override fun updateTribe(tribeId: String, req: UpsertTribeRequest): TribeResult {
        val existing = tribes[tribeId] ?: return TribeResult.NotFound
        val name = req.name.trim()
        if (nameTaken(existing.seasonYear, req.siteId, name, exceptId = tribeId)) return TribeResult.NameTaken
        val dto = existing.dto.copy(name = name, siteId = req.siteId)
        tribes[tribeId] = existing.copy(dto = dto)
        return TribeResult.Ok(dto)
    }

    override fun deleteTribe(tribeId: String): Boolean = tribes.remove(tribeId) != null

    override fun addLeader(tribeId: String, name: String): TribeLeaderDto? {
        val existing = tribes[tribeId] ?: return null
        val leader = TribeLeaderDto(UUID.randomUUID().toString(), name.trim())
        tribes[tribeId] = existing.copy(dto = existing.dto.copy(leaders = existing.dto.leaders + leader))
        return leader
    }

    override fun deleteLeader(leaderId: String): Boolean {
        val (id, tribe) = tribes.entries.firstOrNull { (_, t) -> t.dto.leaders.any { it.id == leaderId } }
            ?: return false
        tribes[id] = tribe.copy(dto = tribe.dto.copy(leaders = tribe.dto.leaders.filter { it.id != leaderId }))
        return true
    }
}

// ---------------------------------------------------------------------------
// Postgres implementation
// ---------------------------------------------------------------------------

class PostgresTribeRepository(private val db: Database) : TribeRepository {

    private fun ResultRow.toLeader() = TribeLeaderDto(
        id = this[TribeLeadersTable.id],
        name = this[TribeLeadersTable.name],
    )

    private fun ResultRow.toTribe(leaders: List<TribeLeaderDto>) = TribeDto(
        id = this[TribesTable.id],
        name = this[TribesTable.name],
        siteId = this[TribesTable.siteId],
        leaders = leaders,
    )

    override fun listTribes(seasonYear: String): List<TribeDto> = transaction(db) {
        val tribeRows = TribesTable.selectAll().where { TribesTable.seasonYear eq seasonYear }.toList()
        val ids = tribeRows.map { it[TribesTable.id] }.toSet()
        val leaders = if (ids.isEmpty()) emptyMap() else
            TribeLeadersTable.selectAll()
                .where { TribeLeadersTable.tribeId inList ids }
                .orderBy(TribeLeadersTable.sortOrder)
                .groupBy({ it[TribeLeadersTable.tribeId] }, { it.toLeader() })
        tribeRows.map { it.toTribe(leaders[it[TribesTable.id]].orEmpty()) }
            .sortedWith(TRIBE_ORDER)
    }

    private fun nameTaken(seasonYear: String, siteId: String?, name: String, exceptId: String?): Boolean =
        TribesTable.selectAll()
            .where {
                (TribesTable.seasonYear eq seasonYear) and (TribesTable.name.lowerCase() eq name.lowercase())
            }
            .any { it[TribesTable.siteId] == siteId && it[TribesTable.id] != exceptId }

    override fun addTribe(seasonYear: String, req: UpsertTribeRequest): TribeResult = transaction(db) {
        val name = req.name.trim()
        if (nameTaken(seasonYear, req.siteId, name, exceptId = null)) return@transaction TribeResult.NameTaken
        val tribeId = UUID.randomUUID().toString()
        TribesTable.insert {
            it[id] = tribeId
            it[TribesTable.seasonYear] = seasonYear
            it[siteId] = req.siteId
            it[TribesTable.name] = name
        }
        TribeResult.Ok(TribeDto(tribeId, name, req.siteId))
    }

    override fun updateTribe(tribeId: String, req: UpsertTribeRequest): TribeResult = transaction(db) {
        val row = TribesTable.selectAll().where { TribesTable.id eq tribeId }.singleOrNull()
            ?: return@transaction TribeResult.NotFound
        val name = req.name.trim()
        if (nameTaken(row[TribesTable.seasonYear], req.siteId, name, exceptId = tribeId)) {
            return@transaction TribeResult.NameTaken
        }
        TribesTable.update({ TribesTable.id eq tribeId }) {
            it[TribesTable.name] = name
            it[siteId] = req.siteId
        }
        val leaders = TribeLeadersTable.selectAll()
            .where { TribeLeadersTable.tribeId eq tribeId }
            .orderBy(TribeLeadersTable.sortOrder)
            .map { it.toLeader() }
        TribeResult.Ok(TribeDto(tribeId, name, req.siteId, leaders))
    }

    override fun deleteTribe(tribeId: String): Boolean = transaction(db) {
        TribeLeadersTable.deleteWhere { TribeLeadersTable.tribeId eq tribeId }
        TribesTable.deleteWhere { TribesTable.id eq tribeId } > 0
    }

    override fun addLeader(tribeId: String, name: String): TribeLeaderDto? = transaction(db) {
        TribesTable.selectAll().where { TribesTable.id eq tribeId }.singleOrNull() ?: return@transaction null
        val order = TribeLeadersTable.selectAll()
            .where { TribeLeadersTable.tribeId eq tribeId }.count().toInt()
        val leaderId = UUID.randomUUID().toString()
        TribeLeadersTable.insert {
            it[id] = leaderId
            it[TribeLeadersTable.tribeId] = tribeId
            it[TribeLeadersTable.name] = name.trim()
            it[sortOrder] = order
        }
        TribeLeaderDto(leaderId, name.trim())
    }

    override fun deleteLeader(leaderId: String): Boolean = transaction(db) {
        TribeLeadersTable.deleteWhere { TribeLeadersTable.id eq leaderId } > 0
    }
}
