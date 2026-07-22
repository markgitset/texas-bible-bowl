package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.CabinAssignmentDto
import net.markdrew.biblebowl.api.CabinDto
import net.markdrew.biblebowl.api.CheckoutDutyDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.UpsertCabinRequest
import org.jetbrains.exposed.v1.core.JoinType
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
 * Outcome of adding/renaming a cabin: the per-(season, site) name uniqueness is reported
 * distinctly so the route can explain the collision.
 */
sealed interface CabinResult {
    data class Ok(val cabin: CabinDto) : CabinResult
    data object NotFound : CabinResult
    /** Another cabin at the same season + site already has this name (case-insensitively). */
    data object NameTaken : CabinResult
}

/**
 * Housing / cabin assignments (item 15, F9): per-season cabins, their free-form assignment rows,
 * and the per-congregation check-out duty roster. Deliberately thin — occupant counts and names
 * are derived by clients from the registration desk, never stored here. Congregation *names* on
 * the DTOs are left null/blank; routes resolve them via [CongregationRepository].
 */
interface HousingRepository {
    /** Every cabin for [seasonYear] with its assignment rows, sorted by site then name. */
    fun listCabins(seasonYear: String): List<CabinDto>
    fun addCabin(seasonYear: String, req: UpsertCabinRequest): CabinResult
    fun updateCabin(cabinId: String, req: UpsertCabinRequest): CabinResult
    /** Deletes a cabin and its assignment rows. */
    fun deleteCabin(cabinId: String): Boolean
    /** Adds an assignment row; null when the cabin doesn't exist. */
    fun addAssignment(cabinId: String, req: AddCabinAssignmentRequest): CabinAssignmentDto?
    fun deleteAssignment(assignmentId: String): Boolean
    /** The check-out duties recorded for [seasonYear] (only congregations with one set). */
    fun listDuties(seasonYear: String): List<CheckoutDutyDto>
    /** Sets the congregation's check-out adult; a blank [adultName] clears the duty. */
    fun setDuty(seasonYear: String, congregationId: String, adultName: String)
}

private val CABIN_ORDER = compareBy<CabinDto>({ it.siteId ?: "" }, { it.name.lowercase() })

// ---------------------------------------------------------------------------
// In-memory implementation (no DATABASE_URL: local dev & tests)
// ---------------------------------------------------------------------------

class InMemoryHousingRepository : HousingRepository {
    private data class Cabin(val seasonYear: String, val dto: CabinDto)

    private val cabins = ConcurrentHashMap<String, Cabin>()
    private val duties = ConcurrentHashMap<Pair<String, String>, String>() // (season, congregation) -> adult

    override fun listCabins(seasonYear: String): List<CabinDto> =
        cabins.values.filter { it.seasonYear == seasonYear }.map { it.dto }.sortedWith(CABIN_ORDER)

    private fun nameTaken(seasonYear: String, siteId: String?, name: String, exceptId: String?): Boolean =
        cabins.values.any {
            it.seasonYear == seasonYear && it.dto.siteId == siteId && it.dto.id != exceptId &&
                it.dto.name.equals(name, ignoreCase = true)
        }

    override fun addCabin(seasonYear: String, req: UpsertCabinRequest): CabinResult {
        val name = req.name.trim()
        if (nameTaken(seasonYear, req.siteId, name, exceptId = null)) return CabinResult.NameTaken
        val dto = CabinDto(UUID.randomUUID().toString(), name, req.siteId, req.capacity)
        cabins[dto.id] = Cabin(seasonYear, dto)
        return CabinResult.Ok(dto)
    }

    override fun updateCabin(cabinId: String, req: UpsertCabinRequest): CabinResult {
        val existing = cabins[cabinId] ?: return CabinResult.NotFound
        val name = req.name.trim()
        if (nameTaken(existing.seasonYear, req.siteId, name, exceptId = cabinId)) return CabinResult.NameTaken
        val dto = existing.dto.copy(name = name, siteId = req.siteId, capacity = req.capacity)
        cabins[cabinId] = existing.copy(dto = dto)
        return CabinResult.Ok(dto)
    }

    override fun deleteCabin(cabinId: String): Boolean = cabins.remove(cabinId) != null

    override fun addAssignment(cabinId: String, req: AddCabinAssignmentRequest): CabinAssignmentDto? {
        val existing = cabins[cabinId] ?: return null
        val row = CabinAssignmentDto(
            id = UUID.randomUUID().toString(),
            congregationId = req.congregationId,
            gender = req.gender,
            label = req.label.trim(),
        )
        cabins[cabinId] = existing.copy(dto = existing.dto.copy(assignments = existing.dto.assignments + row))
        return row
    }

    override fun deleteAssignment(assignmentId: String): Boolean {
        val (id, cabin) = cabins.entries.firstOrNull { (_, c) -> c.dto.assignments.any { it.id == assignmentId } }
            ?: return false
        cabins[id] = cabin.copy(dto = cabin.dto.copy(assignments = cabin.dto.assignments.filter { it.id != assignmentId }))
        return true
    }

    override fun listDuties(seasonYear: String): List<CheckoutDutyDto> =
        duties.entries.filter { it.key.first == seasonYear }
            .map { CheckoutDutyDto(congregationId = it.key.second, adultName = it.value) }
            .sortedBy { it.congregationId }

    override fun setDuty(seasonYear: String, congregationId: String, adultName: String) {
        val name = adultName.trim()
        if (name.isEmpty()) duties.remove(seasonYear to congregationId) else duties[seasonYear to congregationId] = name
    }
}

// ---------------------------------------------------------------------------
// Postgres implementation
// ---------------------------------------------------------------------------

class PostgresHousingRepository(private val db: Database) : HousingRepository {

    private fun ResultRow.toAssignment() = CabinAssignmentDto(
        id = this[CabinAssignmentsTable.id],
        congregationId = this[CabinAssignmentsTable.congregationId],
        gender = this[CabinAssignmentsTable.gender]?.let { Gender.valueOf(it) },
        label = this[CabinAssignmentsTable.label],
    )

    private fun ResultRow.toCabin(assignments: List<CabinAssignmentDto>) = CabinDto(
        id = this[CabinsTable.id],
        name = this[CabinsTable.name],
        siteId = this[CabinsTable.siteId],
        capacity = this[CabinsTable.capacity],
        assignments = assignments,
    )

    override fun listCabins(seasonYear: String): List<CabinDto> = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction emptyList()
        val cabinRows = CabinsTable.selectAll().where { CabinsTable.seasonYear eq year }.toList()
        val ids = cabinRows.map { it[CabinsTable.id] }.toSet()
        val assignments = if (ids.isEmpty()) emptyMap() else
            CabinAssignmentsTable.selectAll()
                .where { CabinAssignmentsTable.cabinId inList ids }
                .orderBy(CabinAssignmentsTable.sortOrder)
                .groupBy({ it[CabinAssignmentsTable.cabinId] }, { it.toAssignment() })
        cabinRows.map { it.toCabin(assignments[it[CabinsTable.id]].orEmpty()) }
            .sortedWith(CABIN_ORDER)
    }

    private fun nameTaken(seasonYear: Int, siteId: String, name: String, exceptId: String?): Boolean =
        CabinsTable.selectAll()
            .where {
                (CabinsTable.seasonYear eq seasonYear) and (CabinsTable.name.lowerCase() eq name.lowercase())
            }
            .any { it[CabinsTable.siteId] == siteId && it[CabinsTable.id] != exceptId }

    override fun addCabin(seasonYear: String, req: UpsertCabinRequest): CabinResult = transaction(db) {
        val year = seasonYear.toInt()
        val name = req.name.trim()
        val site = resolveOrCreateSeasonSite(year, req.siteId)
        if (nameTaken(year, site, name, exceptId = null)) return@transaction CabinResult.NameTaken
        val cabinId = UUID.randomUUID().toString()
        CabinsTable.insert {
            it[id] = cabinId
            it[CabinsTable.seasonYear] = year
            it[siteId] = site
            it[CabinsTable.name] = name
            it[capacity] = req.capacity
        }
        CabinResult.Ok(CabinDto(cabinId, name, site, req.capacity))
    }

    override fun updateCabin(cabinId: String, req: UpsertCabinRequest): CabinResult = transaction(db) {
        val row = CabinsTable.selectAll().where { CabinsTable.id eq cabinId }.singleOrNull()
            ?: return@transaction CabinResult.NotFound
        val year = row[CabinsTable.seasonYear]
        val name = req.name.trim()
        val site = resolveOrCreateSeasonSite(year, req.siteId)
        if (nameTaken(year, site, name, exceptId = cabinId)) {
            return@transaction CabinResult.NameTaken
        }
        CabinsTable.update({ CabinsTable.id eq cabinId }) {
            it[CabinsTable.name] = name
            it[siteId] = site
            it[capacity] = req.capacity
        }
        val assignments = CabinAssignmentsTable.selectAll()
            .where { CabinAssignmentsTable.cabinId eq cabinId }
            .orderBy(CabinAssignmentsTable.sortOrder)
            .map { it.toAssignment() }
        CabinResult.Ok(CabinDto(cabinId, name, site, req.capacity, assignments))
    }

    override fun deleteCabin(cabinId: String): Boolean = transaction(db) {
        CabinAssignmentsTable.deleteWhere { CabinAssignmentsTable.cabinId eq cabinId }
        CabinsTable.deleteWhere { CabinsTable.id eq cabinId } > 0
    }

    override fun addAssignment(cabinId: String, req: AddCabinAssignmentRequest): CabinAssignmentDto? =
        transaction(db) {
            CabinsTable.selectAll().where { CabinsTable.id eq cabinId }.singleOrNull() ?: return@transaction null
            val order = CabinAssignmentsTable.selectAll()
                .where { CabinAssignmentsTable.cabinId eq cabinId }.count().toInt()
            val rowId = UUID.randomUUID().toString()
            CabinAssignmentsTable.insert {
                it[id] = rowId
                it[CabinAssignmentsTable.cabinId] = cabinId
                it[congregationId] = req.congregationId
                it[gender] = req.gender?.name
                it[label] = req.label.trim()
                it[sortOrder] = order
            }
            CabinAssignmentDto(rowId, req.congregationId, null, req.gender, req.label.trim())
        }

    override fun deleteAssignment(assignmentId: String): Boolean = transaction(db) {
        CabinAssignmentsTable.deleteWhere { CabinAssignmentsTable.id eq assignmentId } > 0
    }

    override fun listDuties(seasonYear: String): List<CheckoutDutyDto> = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction emptyList()
        (CheckoutDutiesTable innerJoin PeopleTable)
            .selectAll().where { CheckoutDutiesTable.seasonYear eq year }
            .map {
                CheckoutDutyDto(
                    congregationId = it[CheckoutDutiesTable.congregationId],
                    adultName = it[PeopleTable.name],
                )
            }
            .sortedBy { it.congregationId }
    }

    override fun setDuty(seasonYear: String, congregationId: String, adultName: String): Unit = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction
        val name = adultName.trim()
        val where = (CheckoutDutiesTable.seasonYear eq year) and
            (CheckoutDutiesTable.congregationId eq congregationId)
        if (name.isEmpty()) {
            CheckoutDutiesTable.deleteWhere { where }
            return@transaction
        }
        ensureSeasonRow(year)
        val personId = resolveDutyPerson(congregationId, name)
        val updated = CheckoutDutiesTable.update({ where }) { it[CheckoutDutiesTable.personId] = personId }
        if (updated == 0) {
            CheckoutDutiesTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[CheckoutDutiesTable.seasonYear] = year
                it[CheckoutDutiesTable.congregationId] = congregationId
                it[CheckoutDutiesTable.personId] = personId
            }
        }
    }

    /**
     * The person a check-out duty names: matched by name among the congregation's people (anyone
     * with a participation there, any season), else a minimal person is created — check-out staff
     * aren't necessarily registered attendees, so the free-form name from the desk still resolves.
     */
    private fun resolveDutyPerson(congregationId: String, name: String): String {
        val existing = (PeopleTable innerJoin ParticipantsTable)
            .join(RegistrationsTable, JoinType.INNER, onColumn = ParticipantsTable.registrationId, otherColumn = RegistrationsTable.id)
            .selectAll()
            .where {
                (RegistrationsTable.congregationId eq congregationId) and
                    (PeopleTable.name.lowerCase() eq name.lowercase())
            }
            .firstOrNull()?.get(PeopleTable.id)
        if (existing != null) return existing
        val newId = UUID.randomUUID().toString()
        PeopleTable.insert {
            it[id] = newId
            it[PeopleTable.name] = name
            it[isAdult] = true
            it[claimCode] = freshPersonClaimCode()
        }
        return newId
    }
}
