package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.congregationCodeCandidates
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The maximum contestants per team (competition rule; enforced at the repository layer). */
const val MAX_TEAM_SIZE = 4

/**
 * Outcome of creating a congregation: the two uniqueness constraints (name+city, and the two-letter
 * code) are reported distinctly so the route can explain which one collided.
 */
sealed interface CreateCongregationResult {
    data class Created(val congregation: CongregationDto) : CreateCongregationResult
    /** A congregation with the same name+city already exists. */
    data object NameCityTaken : CreateCongregationResult
    /** The chosen two-letter code is already used by another congregation. */
    data object CodeTaken : CreateCongregationResult
}

/**
 * Outcome of a congregation update: the two uniqueness constraints (name+city, and the two-letter
 * code) are reported distinctly so the route can explain which one collided. The "code locked once
 * set" rule is an authorization concern and lives in the route, not here.
 */
sealed interface UpdateCongregationResult {
    data class Updated(val congregation: CongregationDto) : UpdateCongregationResult
    data object NotFound : UpdateCongregationResult
    /** The new name+city already belongs to a different congregation. */
    data object NameCityTaken : UpdateCongregationResult
    /** The chosen two-letter code is already used by a different congregation. */
    data object CodeTaken : UpdateCongregationResult
}

interface CongregationRepository {
    /**
     * Creates a congregation (its [CreateCongregationRequest.code], if any, is stored uppercased).
     * Reports name+city and code collisions distinctly.
     */
    fun create(req: CreateCongregationRequest, createdByUserId: String): CreateCongregationResult
    /**
     * Updates a congregation's editable fields (name, address, city, state, ZIP, and its two-letter
     * [CongregationDto.code]). State and code are normalized to uppercase. Reports collisions on
     * name+city or code distinctly; the route enforces who may change an already-set code.
     */
    fun update(id: String, req: UpdateCongregationRequest): UpdateCongregationResult
    /** The most mnemonic two-letter code for [name] that no congregation is using yet (see [congregationCodeCandidates]). */
    fun suggestCode(name: String): String
    fun findById(id: String): CongregationDto?
    fun findByIds(ids: Collection<String>): List<CongregationDto>
    /** Case-insensitive substring search over name and city, for the step-1 typeahead. */
    fun search(query: String): List<CongregationDto>
    /** Every congregation, sorted by name (registration desk). */
    fun listAll(): List<CongregationDto>
}

/** Outcome of adding a roster entry — the roster cap is enforced inside the repository transaction. */
sealed interface AddMemberResult {
    data class Added(val entry: RosterEntryDto) : AddMemberResult
    data object RosterFull : AddMemberResult
    data object TeamNotFound : AddMemberResult
}

/** Outcome of claiming a roster entry by its coach-shared code. */
sealed interface ClaimResult {
    data class Claimed(val entry: RosterEntryDto) : ClaimResult
    data object NotFound : ClaimResult
    /** The code matches an entry already claimed by a different account. */
    data object AlreadyClaimed : ClaimResult
}

/**
 * A congregation's registration for one season (unique per congregation+seasonYear), with its
 * teams and rosters. [RegistrationDto.totalCents] is left null here — routes compute it from the
 * current season's fees.
 */
interface RegistrationRepository {
    fun find(congregationId: String, seasonYear: String): RegistrationDto?
    /** Adds a team (creating the draft registration if needed); null on a duplicate team name. */
    fun addTeam(congregationId: String, seasonYear: String, name: String): TeamDto?
    fun renameTeam(teamId: String, name: String): TeamDto?
    fun deleteTeam(teamId: String): Boolean
    fun addMember(teamId: String, req: UpsertRosterEntryRequest): AddMemberResult
    fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto?
    fun deleteMember(memberId: String): Boolean
    /** Adds an individual (adult) contestant, creating the draft registration if needed. */
    fun addIndividual(congregationId: String, seasonYear: String, req: UpsertIndividualRequest): RosterEntryDto
    fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto?
    fun deleteIndividual(individualId: String): Boolean
    /** Marks the registration SUBMITTED (idempotent; re-submit refreshes the timestamp). */
    fun submit(congregationId: String, seasonYear: String): RegistrationDto?
    /** Scoping lookups: which congregation a team/member/individual belongs to (for permission checks). */
    fun congregationIdForTeam(teamId: String): String?
    fun congregationIdForMember(memberId: String): String?
    fun congregationIdForIndividual(individualId: String): String?
    /** Every registration for [seasonYear], with full teams/rosters (registration desk). */
    fun listForSeason(seasonYear: String): List<RegistrationDto>
    /** Sets (non-null) or clears (null) payment received. Null return = no such registration. */
    fun setPaid(registrationId: String, paidAtEpochMs: Long?): RegistrationDto?
    /** Links the entry with claim code [code] to [userId]. Idempotent for the same account. */
    fun claimEntry(code: String, userId: String): ClaimResult
    /** Ids of every roster entry (team member or individual) claimed by [userId]. */
    fun entryIdsOwnedBy(userId: String): Set<String>
}

// ---------------------------------------------------------------------------
// In-memory implementations (no DATABASE_URL: local dev & tests)
// ---------------------------------------------------------------------------

class InMemoryCongregationRepository : CongregationRepository {
    private val byId = ConcurrentHashMap<String, CongregationDto>()

    override fun create(req: CreateCongregationRequest, createdByUserId: String): CreateCongregationResult {
        val n = req.name.trim()
        val c = req.city.trim()
        val code = req.code.trim().uppercase()
        synchronized(byId) {
            if (byId.values.any { it.name.equals(n, ignoreCase = true) && it.city.equals(c, ignoreCase = true) }) {
                return CreateCongregationResult.NameCityTaken
            }
            if (code.isNotBlank() && byId.values.any { it.code.equals(code, ignoreCase = true) }) {
                return CreateCongregationResult.CodeTaken
            }
            val dto = CongregationDto(
                id = UUID.randomUUID().toString(),
                name = n,
                city = c,
                state = req.state.trim().uppercase(),
                mailingAddress = req.mailingAddress.trim(),
                zip = req.zip.trim(),
                code = code,
            )
            byId[dto.id] = dto
            return CreateCongregationResult.Created(dto)
        }
    }

    override fun suggestCode(name: String): String = synchronized(byId) {
        val taken = byId.values.mapNotNull { it.code.takeIf(String::isNotBlank)?.uppercase() }.toSet()
        congregationCodeCandidates(name).firstOrNull { it !in taken } ?: ""
    }

    override fun update(id: String, req: UpdateCongregationRequest): UpdateCongregationResult = synchronized(byId) {
        val existing = byId[id] ?: return UpdateCongregationResult.NotFound
        val n = req.name.trim()
        val c = req.city.trim()
        val code = req.code.trim().uppercase()
        if (byId.values.any { it.id != id && it.name.equals(n, ignoreCase = true) && it.city.equals(c, ignoreCase = true) }) {
            return UpdateCongregationResult.NameCityTaken
        }
        if (code.isNotBlank() && byId.values.any { it.id != id && it.code.equals(code, ignoreCase = true) }) {
            return UpdateCongregationResult.CodeTaken
        }
        val updated = existing.copy(
            name = n,
            city = c,
            state = req.state.trim().uppercase(),
            mailingAddress = req.mailingAddress.trim(),
            zip = req.zip.trim(),
            code = code,
        )
        byId[id] = updated
        return UpdateCongregationResult.Updated(updated)
    }

    override fun findById(id: String): CongregationDto? = byId[id]
    override fun findByIds(ids: Collection<String>): List<CongregationDto> = ids.mapNotNull { byId[it] }

    override fun search(query: String): List<CongregationDto> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return byId.values
            .filter { it.name.contains(q, ignoreCase = true) || it.city.contains(q, ignoreCase = true) }
            .sortedBy { it.name }
    }

    override fun listAll(): List<CongregationDto> = byId.values.sortedBy { it.name.lowercase() }
}

class InMemoryRegistrationRepository(
    private val congregations: CongregationRepository,
) : RegistrationRepository {

    private data class Reg(
        val id: String,
        val congregationId: String,
        val seasonYear: String,
        var status: RegistrationStatus,
        var submittedAtMs: Long?,
        var paidAtMs: Long? = null,
        val teamIds: MutableList<String> = mutableListOf(),
        val individualIds: MutableList<String> = mutableListOf(),
    )

    private data class Team(val id: String, val regId: String, var name: String, val memberIds: MutableList<String> = mutableListOf())

    private val regs = mutableMapOf<String, Reg>() // key = "$congregationId|$seasonYear"
    private val teams = mutableMapOf<String, Team>()
    private val members = mutableMapOf<String, RosterEntryDto>()
    private val memberTeam = mutableMapOf<String, String>()
    private val individuals = mutableMapOf<String, RosterEntryDto>()
    private val individualReg = mutableMapOf<String, String>()
    private val usedCodes = mutableSetOf<String>()
    private val entryOwner = mutableMapOf<String, String>()
    private val lock = Any()

    override fun find(congregationId: String, seasonYear: String): RegistrationDto? = synchronized(lock) {
        regs["$congregationId|$seasonYear"]?.toDto()
    }

    private fun regFor(congregationId: String, seasonYear: String): Reg =
        regs.getOrPut("$congregationId|$seasonYear") {
            Reg(UUID.randomUUID().toString(), congregationId, seasonYear, RegistrationStatus.DRAFT, null)
        }

    override fun addTeam(congregationId: String, seasonYear: String, name: String): TeamDto? = synchronized(lock) {
        val reg = regFor(congregationId, seasonYear)
        val trimmed = name.trim()
        if (reg.teamIds.any { teams[it]?.name.equals(trimmed, ignoreCase = true) }) return null
        val team = Team(UUID.randomUUID().toString(), reg.id, trimmed)
        teams[team.id] = team
        reg.teamIds += team.id
        team.toDto()
    }

    override fun renameTeam(teamId: String, name: String): TeamDto? = synchronized(lock) {
        val team = teams[teamId] ?: return null
        team.name = name.trim()
        team.toDto()
    }

    override fun deleteTeam(teamId: String): Boolean = synchronized(lock) {
        val team = teams.remove(teamId) ?: return false
        team.memberIds.forEach { members.remove(it); memberTeam.remove(it) }
        regs.values.forEach { it.teamIds.remove(teamId) }
        true
    }

    override fun addMember(teamId: String, req: UpsertRosterEntryRequest): AddMemberResult = synchronized(lock) {
        val team = teams[teamId] ?: return AddMemberResult.TeamNotFound
        if (team.memberIds.size >= MAX_TEAM_SIZE) return AddMemberResult.RosterFull
        val reg = regs.values.first { it.id == team.regId }
        val code = generateSequence { ClaimCodes.generate() }.first { usedCodes.add(it) }
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            birthdate = req.birthdate,
            shirtSize = req.shirtSize,
            gender = req.gender,
            firstSeasonYear = resolveFirstSeasonYear(reg.congregationId, reg.seasonYear, req.name, req.inexperienced),
            claimCode = code,
        )
        members[entry.id] = entry
        memberTeam[entry.id] = teamId
        team.memberIds += entry.id
        AddMemberResult.Added(entry)
    }

    override fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto? = synchronized(lock) {
        val entry = members[memberId] ?: return null
        val reg = memberTeam[memberId]?.let { teams[it] }?.let { team -> regs.values.first { it.id == team.regId } }
        val updated = entry.copy(
            name = req.name.trim(),
            birthdate = req.birthdate,
            shirtSize = req.shirtSize,
            gender = req.gender,
            firstSeasonYear = reg?.let {
                resolveFirstSeasonYear(it.congregationId, it.seasonYear, req.name, req.inexperienced)
            },
        )
        members[memberId] = updated
        updated
    }

    /**
     * The first season year to store for a team contestant: any same-named entry on one of this
     * congregation's earlier-season rosters wins (its own first year, or failing that the season it
     * appeared in) — that's what turns last year's first-year contestant into this year's
     * experienced one automatically. With no history: the current season when the coach marked
     * them inexperienced, else null (experienced, first year unknown).
     */
    private fun resolveFirstSeasonYear(
        congregationId: String,
        seasonYear: String,
        name: String,
        inexperienced: Boolean,
    ): String? {
        val trimmed = name.trim()
        val priorYears = regs.values
            .filter { it.congregationId == congregationId && it.seasonYear < seasonYear }
            .flatMap { reg ->
                reg.teamIds.mapNotNull { teams[it] }
                    .flatMap { team -> team.memberIds.mapNotNull { members[it] } }
                    .filter { it.name.equals(trimmed, ignoreCase = true) }
                    .map { it.firstSeasonYear ?: reg.seasonYear }
            }
        return priorYears.minOrNull() ?: seasonYear.takeIf { inexperienced }
    }

    override fun deleteMember(memberId: String): Boolean = synchronized(lock) {
        val entry = members.remove(memberId) ?: return false
        usedCodes.remove(entry.claimCode)
        memberTeam.remove(memberId)
        teams.values.forEach { it.memberIds.remove(memberId) }
        true
    }

    override fun addIndividual(
        congregationId: String,
        seasonYear: String,
        req: UpsertIndividualRequest,
    ): RosterEntryDto = synchronized(lock) {
        val reg = regFor(congregationId, seasonYear)
        val code = generateSequence { ClaimCodes.generate() }.first { usedCodes.add(it) }
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            birthdate = null,
            shirtSize = req.shirtSize,
            gender = req.gender,
            claimCode = code,
        )
        individuals[entry.id] = entry
        individualReg[entry.id] = reg.id
        reg.individualIds += entry.id
        entry
    }

    override fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto? =
        synchronized(lock) {
            val entry = individuals[individualId] ?: return null
            val updated = entry.copy(name = req.name.trim(), shirtSize = req.shirtSize, gender = req.gender)
            individuals[individualId] = updated
            updated
        }

    override fun deleteIndividual(individualId: String): Boolean = synchronized(lock) {
        val entry = individuals.remove(individualId) ?: return false
        usedCodes.remove(entry.claimCode)
        individualReg.remove(individualId)
        regs.values.forEach { it.individualIds.remove(individualId) }
        true
    }

    override fun submit(congregationId: String, seasonYear: String): RegistrationDto? = synchronized(lock) {
        val reg = regs["$congregationId|$seasonYear"] ?: return null
        reg.status = RegistrationStatus.SUBMITTED
        reg.submittedAtMs = System.currentTimeMillis()
        reg.toDto()
    }

    override fun congregationIdForTeam(teamId: String): String? = synchronized(lock) {
        teams[teamId]?.let { team -> regs.values.firstOrNull { it.id == team.regId }?.congregationId }
    }

    override fun congregationIdForMember(memberId: String): String? = synchronized(lock) {
        memberTeam[memberId]?.let { congregationIdForTeam(it) }
    }

    override fun congregationIdForIndividual(individualId: String): String? = synchronized(lock) {
        individualReg[individualId]?.let { regId -> regs.values.firstOrNull { it.id == regId }?.congregationId }
    }

    override fun listForSeason(seasonYear: String): List<RegistrationDto> = synchronized(lock) {
        regs.values.filter { it.seasonYear == seasonYear }.map { it.toDto() }
    }

    override fun setPaid(registrationId: String, paidAtEpochMs: Long?): RegistrationDto? = synchronized(lock) {
        val reg = regs.values.firstOrNull { it.id == registrationId } ?: return null
        reg.paidAtMs = paidAtEpochMs
        reg.toDto()
    }

    override fun claimEntry(code: String, userId: String): ClaimResult = synchronized(lock) {
        val entry = (members.values + individuals.values).firstOrNull { it.claimCode == code }
            ?: return ClaimResult.NotFound
        val owner = entryOwner[entry.id]
        if (owner != null && owner != userId) return ClaimResult.AlreadyClaimed
        entryOwner[entry.id] = userId
        val claimed = entry.copy(claimed = true)
        if (entry.id in members) members[entry.id] = claimed else individuals[entry.id] = claimed
        ClaimResult.Claimed(claimed)
    }

    override fun entryIdsOwnedBy(userId: String): Set<String> = synchronized(lock) {
        entryOwner.filterValues { it == userId }.keys.toSet()
    }

    private fun Team.toDto() = TeamDto(id, name, memberIds.mapNotNull { members[it] })

    private fun Reg.toDto(): RegistrationDto = RegistrationDto(
        id = id,
        congregation = congregations.findById(congregationId)
            ?: CongregationDto(congregationId, "?", "?"),
        seasonYear = seasonYear,
        status = status,
        teams = teamIds.mapNotNull { teams[it]?.toDto() },
        individuals = individualIds.mapNotNull { individuals[it] },
        submittedAt = submittedAtMs?.let { Instant.ofEpochMilli(it).toString() },
        paidAt = paidAtMs?.let { Instant.ofEpochMilli(it).toString() },
    )
}

// ---------------------------------------------------------------------------
// Postgres implementations
// ---------------------------------------------------------------------------

class PostgresCongregationRepository(private val db: Database) : CongregationRepository {

    override fun create(req: CreateCongregationRequest, createdByUserId: String): CreateCongregationResult = transaction(db) {
        val n = req.name.trim()
        val c = req.city.trim()
        val code = req.code.trim().uppercase()
        val state = req.state.trim().uppercase()
        val nameCityTaken = CongregationsTable.selectAll()
            .where {
                (CongregationsTable.name.lowerCase() eq n.lowercase()) and
                    (CongregationsTable.city.lowerCase() eq c.lowercase())
            }
            .any()
        if (nameCityTaken) return@transaction CreateCongregationResult.NameCityTaken
        if (code.isNotBlank() && CongregationsTable.selectAll().where { CongregationsTable.code.lowerCase() eq code.lowercase() }.any()) {
            return@transaction CreateCongregationResult.CodeTaken
        }
        val newId = UUID.randomUUID().toString()
        CongregationsTable.insert {
            it[id] = newId
            it[CongregationsTable.name] = n
            it[CongregationsTable.city] = c
            it[CongregationsTable.state] = state
            it[mailingAddress] = req.mailingAddress.trim()
            it[zip] = req.zip.trim()
            it[CongregationsTable.code] = code
            it[CongregationsTable.createdByUserId] = createdByUserId
            it[createdAtEpochMs] = System.currentTimeMillis()
        }
        CreateCongregationResult.Created(
            CongregationDto(newId, n, c, state, req.mailingAddress.trim(), req.zip.trim(), code),
        )
    }

    override fun suggestCode(name: String): String = transaction(db) {
        val taken = CongregationsTable.selectAll()
            .mapNotNull { it[CongregationsTable.code].takeIf(String::isNotBlank)?.uppercase() }
            .toSet()
        congregationCodeCandidates(name).firstOrNull { it !in taken } ?: ""
    }

    override fun update(id: String, req: UpdateCongregationRequest): UpdateCongregationResult = transaction(db) {
        if (CongregationsTable.selectAll().where { CongregationsTable.id eq id }.none()) {
            return@transaction UpdateCongregationResult.NotFound
        }
        val n = req.name.trim()
        val c = req.city.trim()
        val code = req.code.trim().uppercase()
        val nameCityTaken = CongregationsTable.selectAll()
            .where {
                (CongregationsTable.name.lowerCase() eq n.lowercase()) and
                    (CongregationsTable.city.lowerCase() eq c.lowercase())
            }
            .any { it[CongregationsTable.id] != id }
        if (nameCityTaken) return@transaction UpdateCongregationResult.NameCityTaken
        if (code.isNotBlank()) {
            val codeTaken = CongregationsTable.selectAll()
                .where { CongregationsTable.code.lowerCase() eq code.lowercase() }
                .any { it[CongregationsTable.id] != id }
            if (codeTaken) return@transaction UpdateCongregationResult.CodeTaken
        }
        CongregationsTable.update({ CongregationsTable.id eq id }) {
            it[name] = n
            it[city] = c
            it[state] = req.state.trim().uppercase()
            it[mailingAddress] = req.mailingAddress.trim()
            it[zip] = req.zip.trim()
            it[CongregationsTable.code] = code
        }
        UpdateCongregationResult.Updated(
            CongregationsTable.selectAll().where { CongregationsTable.id eq id }.single().toDto(),
        )
    }

    override fun findById(id: String): CongregationDto? = transaction(db) {
        CongregationsTable.selectAll().where { CongregationsTable.id eq id }.singleOrNull()?.toDto()
    }

    override fun findByIds(ids: Collection<String>): List<CongregationDto> = transaction(db) {
        if (ids.isEmpty()) emptyList()
        else CongregationsTable.selectAll().where { CongregationsTable.id inList ids }.map { it.toDto() }
    }

    override fun search(query: String): List<CongregationDto> = transaction(db) {
        val q = "%${query.trim().lowercase()}%"
        if (query.isBlank()) emptyList()
        else CongregationsTable.selectAll()
            .where { (CongregationsTable.name.lowerCase() like q) or (CongregationsTable.city.lowerCase() like q) }
            .orderBy(CongregationsTable.name)
            .map { it.toDto() }
    }

    override fun listAll(): List<CongregationDto> = transaction(db) {
        CongregationsTable.selectAll().orderBy(CongregationsTable.name).map { it.toDto() }
    }

    private fun ResultRow.toDto() = CongregationDto(
        id = this[CongregationsTable.id],
        name = this[CongregationsTable.name],
        city = this[CongregationsTable.city],
        state = this[CongregationsTable.state],
        mailingAddress = this[CongregationsTable.mailingAddress],
        zip = this[CongregationsTable.zip],
        code = this[CongregationsTable.code],
    )
}

class PostgresRegistrationRepository(private val db: Database) : RegistrationRepository {

    override fun find(congregationId: String, seasonYear: String): RegistrationDto? = transaction(db) {
        regRow(congregationId, seasonYear)?.toDto()
    }

    /** The registration's row id, creating the draft registration on first use. */
    private fun regIdFor(congregationId: String, seasonYear: String): String =
        regRow(congregationId, seasonYear)?.get(RegistrationsTable.id) ?: run {
            val newId = UUID.randomUUID().toString()
            RegistrationsTable.insert {
                it[id] = newId
                it[RegistrationsTable.congregationId] = congregationId
                it[RegistrationsTable.seasonYear] = seasonYear
                it[status] = RegistrationStatus.DRAFT.name
                it[updatedAtEpochMs] = System.currentTimeMillis()
            }
            newId
        }

    override fun addTeam(congregationId: String, seasonYear: String, name: String): TeamDto? = transaction(db) {
        val regId = regIdFor(congregationId, seasonYear)
        val trimmed = name.trim()
        val dupe = TeamsTable.selectAll()
            .where { (TeamsTable.registrationId eq regId) and (TeamsTable.name.lowerCase() eq trimmed.lowercase()) }
            .any()
        if (dupe) return@transaction null
        val order = TeamsTable.selectAll().where { TeamsTable.registrationId eq regId }.count().toInt()
        val teamId = UUID.randomUUID().toString()
        TeamsTable.insert {
            it[id] = teamId
            it[registrationId] = regId
            it[TeamsTable.name] = trimmed
            it[sortOrder] = order
        }
        touch(regId)
        TeamDto(teamId, trimmed)
    }

    override fun renameTeam(teamId: String, name: String): TeamDto? = transaction(db) {
        val updated = TeamsTable.update({ TeamsTable.id eq teamId }) { it[TeamsTable.name] = name.trim() }
        if (updated == 0) null else teamDto(teamId)
    }

    override fun deleteTeam(teamId: String): Boolean = transaction(db) {
        TeamMembersTable.deleteWhere { TeamMembersTable.teamId eq teamId }
        TeamsTable.deleteWhere { TeamsTable.id eq teamId } > 0
    }

    /** A claim code unused by both roster tables (their unique indexes backstop the collision race). */
    private fun freshClaimCode(): String {
        repeat(5) {
            val code = ClaimCodes.generate()
            val taken = TeamMembersTable.selectAll().where { TeamMembersTable.claimCode eq code }.any() ||
                IndividualsTable.selectAll().where { IndividualsTable.claimCode eq code }.any()
            if (!taken) return code
        }
        error("Could not allocate a unique claim code")
    }

    override fun addMember(teamId: String, req: UpsertRosterEntryRequest): AddMemberResult = transaction(db) {
        val team = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
            ?: return@transaction AddMemberResult.TeamNotFound
        val size = TeamMembersTable.selectAll().where { TeamMembersTable.teamId eq teamId }.count()
        if (size >= MAX_TEAM_SIZE) return@transaction AddMemberResult.RosterFull
        val firstYear = resolveFirstSeasonYear(team[TeamsTable.registrationId], req)
        val code = freshClaimCode()
        val memberId = UUID.randomUUID().toString()
        TeamMembersTable.insert {
            it[id] = memberId
            it[TeamMembersTable.teamId] = teamId
            it[name] = req.name.trim()
            it[birthdate] = req.birthdate
            it[shirtSize] = req.shirtSize.name
            it[gender] = req.gender.name
            it[firstSeasonYear] = firstYear
            it[claimCode] = code
        }
        AddMemberResult.Added(
            RosterEntryDto(memberId, req.name.trim(), req.birthdate, req.shirtSize, req.gender, firstYear, code)
        )
    }

    override fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto? = transaction(db) {
        val regId = TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }.singleOrNull()
            ?.let { row ->
                TeamsTable.selectAll().where { TeamsTable.id eq row[TeamMembersTable.teamId] }
                    .single()[TeamsTable.registrationId]
            } ?: return@transaction null
        val firstYear = resolveFirstSeasonYear(regId, req)
        TeamMembersTable.update({ TeamMembersTable.id eq memberId }) {
            it[name] = req.name.trim()
            it[birthdate] = req.birthdate
            it[shirtSize] = req.shirtSize.name
            it[gender] = req.gender.name
            it[firstSeasonYear] = firstYear
        }
        TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }.single().toEntry()
    }

    /**
     * The first season year to store for a team contestant: any same-named entry on one of this
     * congregation's earlier-season rosters wins (its own first year, or failing that the season it
     * appeared in) — that's what turns last year's first-year contestant into this year's
     * experienced one automatically. With no history: the current season when the coach marked
     * them inexperienced, else null (experienced, first year unknown).
     */
    private fun resolveFirstSeasonYear(regId: String, req: UpsertRosterEntryRequest): String? {
        val reg = RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }.single()
        val congregationId = reg[RegistrationsTable.congregationId]
        val seasonYear = reg[RegistrationsTable.seasonYear]
        val prior = (TeamMembersTable innerJoin TeamsTable innerJoin RegistrationsTable)
            .selectAll()
            .where {
                (RegistrationsTable.congregationId eq congregationId) and
                    (RegistrationsTable.seasonYear less seasonYear) and
                    (TeamMembersTable.name.lowerCase() eq req.name.trim().lowercase())
            }
            .minOfOrNull { it[TeamMembersTable.firstSeasonYear] ?: it[RegistrationsTable.seasonYear] }
        return prior ?: seasonYear.takeIf { req.inexperienced }
    }

    override fun deleteMember(memberId: String): Boolean = transaction(db) {
        TeamMembersTable.deleteWhere { TeamMembersTable.id eq memberId } > 0
    }

    override fun addIndividual(
        congregationId: String,
        seasonYear: String,
        req: UpsertIndividualRequest,
    ): RosterEntryDto = transaction(db) {
        val regId = regIdFor(congregationId, seasonYear)
        val code = freshClaimCode()
        val individualId = UUID.randomUUID().toString()
        IndividualsTable.insert {
            it[id] = individualId
            it[registrationId] = regId
            it[name] = req.name.trim()
            it[shirtSize] = req.shirtSize.name
            it[gender] = req.gender.name
            it[claimCode] = code
        }
        touch(regId)
        RosterEntryDto(
            individualId, req.name.trim(), birthdate = null,
            shirtSize = req.shirtSize, gender = req.gender, claimCode = code,
        )
    }

    override fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto? =
        transaction(db) {
            val updated = IndividualsTable.update({ IndividualsTable.id eq individualId }) {
                it[name] = req.name.trim()
                it[shirtSize] = req.shirtSize.name
                it[gender] = req.gender.name
            }
            if (updated == 0) null
            else IndividualsTable.selectAll().where { IndividualsTable.id eq individualId }.single().toIndividual()
        }

    override fun deleteIndividual(individualId: String): Boolean = transaction(db) {
        IndividualsTable.deleteWhere { IndividualsTable.id eq individualId } > 0
    }

    override fun submit(congregationId: String, seasonYear: String): RegistrationDto? = transaction(db) {
        val regId = regRow(congregationId, seasonYear)?.get(RegistrationsTable.id) ?: return@transaction null
        RegistrationsTable.update({ RegistrationsTable.id eq regId }) {
            it[status] = RegistrationStatus.SUBMITTED.name
            it[submittedAtEpochMs] = System.currentTimeMillis()
            it[updatedAtEpochMs] = System.currentTimeMillis()
        }
        regRow(congregationId, seasonYear)?.toDto()
    }

    override fun congregationIdForTeam(teamId: String): String? = transaction(db) {
        val regId = TeamsTable.selectAll().where { TeamsTable.id eq teamId }
            .singleOrNull()?.get(TeamsTable.registrationId) ?: return@transaction null
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)
    }

    override fun congregationIdForMember(memberId: String): String? = transaction(db) {
        val teamId = TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }
            .singleOrNull()?.get(TeamMembersTable.teamId) ?: return@transaction null
        congregationIdForTeam(teamId)
    }

    override fun congregationIdForIndividual(individualId: String): String? = transaction(db) {
        val regId = IndividualsTable.selectAll().where { IndividualsTable.id eq individualId }
            .singleOrNull()?.get(IndividualsTable.registrationId) ?: return@transaction null
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)
    }

    override fun listForSeason(seasonYear: String): List<RegistrationDto> = transaction(db) {
        RegistrationsTable.selectAll()
            .where { RegistrationsTable.seasonYear eq seasonYear }
            .map { it.toDto() }
    }

    override fun setPaid(registrationId: String, paidAtEpochMs: Long?): RegistrationDto? = transaction(db) {
        val updated = RegistrationsTable.update({ RegistrationsTable.id eq registrationId }) {
            it[RegistrationsTable.paidAtEpochMs] = paidAtEpochMs
            it[updatedAtEpochMs] = System.currentTimeMillis()
        }
        if (updated == 0) null
        else RegistrationsTable.selectAll().where { RegistrationsTable.id eq registrationId }.single().toDto()
    }

    override fun claimEntry(code: String, userId: String): ClaimResult = transaction(db) {
        TeamMembersTable.selectAll().where { TeamMembersTable.claimCode eq code }.singleOrNull()?.let { row ->
            val owner = row[TeamMembersTable.ownerUserId]
            if (owner != null && owner != userId) return@transaction ClaimResult.AlreadyClaimed
            TeamMembersTable.update({ TeamMembersTable.id eq row[TeamMembersTable.id] }) {
                it[ownerUserId] = userId
            }
            return@transaction ClaimResult.Claimed(row.toEntry().copy(claimed = true))
        }
        IndividualsTable.selectAll().where { IndividualsTable.claimCode eq code }.singleOrNull()?.let { row ->
            val owner = row[IndividualsTable.ownerUserId]
            if (owner != null && owner != userId) return@transaction ClaimResult.AlreadyClaimed
            IndividualsTable.update({ IndividualsTable.id eq row[IndividualsTable.id] }) {
                it[ownerUserId] = userId
            }
            return@transaction ClaimResult.Claimed(row.toIndividual().copy(claimed = true))
        }
        ClaimResult.NotFound
    }

    override fun entryIdsOwnedBy(userId: String): Set<String> = transaction(db) {
        val memberIds = TeamMembersTable.selectAll()
            .where { TeamMembersTable.ownerUserId eq userId }
            .map { it[TeamMembersTable.id] }
        val individualIds = IndividualsTable.selectAll()
            .where { IndividualsTable.ownerUserId eq userId }
            .map { it[IndividualsTable.id] }
        (memberIds + individualIds).toSet()
    }

    private fun regRow(congregationId: String, seasonYear: String): ResultRow? =
        RegistrationsTable.selectAll()
            .where {
                (RegistrationsTable.congregationId eq congregationId) and
                    (RegistrationsTable.seasonYear eq seasonYear)
            }
            .singleOrNull()

    private fun touch(regId: String) {
        RegistrationsTable.update({ RegistrationsTable.id eq regId }) {
            it[updatedAtEpochMs] = System.currentTimeMillis()
        }
    }

    private fun teamDto(teamId: String): TeamDto? =
        TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()?.let { row ->
            TeamDto(
                id = row[TeamsTable.id],
                name = row[TeamsTable.name],
                members = TeamMembersTable.selectAll()
                    .where { TeamMembersTable.teamId eq teamId }
                    .map { it.toEntry() },
            )
        }

    private fun ResultRow.toEntry() = RosterEntryDto(
        id = this[TeamMembersTable.id],
        name = this[TeamMembersTable.name],
        birthdate = this[TeamMembersTable.birthdate],
        shirtSize = ShirtSize.valueOf(this[TeamMembersTable.shirtSize]),
        gender = this[TeamMembersTable.gender]?.let { Gender.valueOf(it) },
        firstSeasonYear = this[TeamMembersTable.firstSeasonYear],
        claimCode = this[TeamMembersTable.claimCode],
        claimed = this[TeamMembersTable.ownerUserId] != null,
    )

    private fun ResultRow.toIndividual() = RosterEntryDto(
        id = this[IndividualsTable.id],
        name = this[IndividualsTable.name],
        birthdate = null,
        shirtSize = ShirtSize.valueOf(this[IndividualsTable.shirtSize]),
        gender = this[IndividualsTable.gender]?.let { Gender.valueOf(it) },
        claimCode = this[IndividualsTable.claimCode],
        claimed = this[IndividualsTable.ownerUserId] != null,
    )

    private fun ResultRow.toDto(): RegistrationDto {
        val regId = this[RegistrationsTable.id]
        val congId = this[RegistrationsTable.congregationId]
        val cong = CongregationsTable.selectAll().where { CongregationsTable.id eq congId }.singleOrNull()
        return RegistrationDto(
            id = regId,
            congregation = CongregationDto(
                id = congId,
                name = cong?.get(CongregationsTable.name) ?: "?",
                city = cong?.get(CongregationsTable.city) ?: "?",
                state = cong?.get(CongregationsTable.state) ?: "",
                mailingAddress = cong?.get(CongregationsTable.mailingAddress) ?: "",
                zip = cong?.get(CongregationsTable.zip) ?: "",
                code = cong?.get(CongregationsTable.code) ?: "",
            ),
            seasonYear = this[RegistrationsTable.seasonYear],
            status = RegistrationStatus.valueOf(this[RegistrationsTable.status]),
            teams = TeamsTable.selectAll()
                .where { TeamsTable.registrationId eq regId }
                .orderBy(TeamsTable.sortOrder)
                .map { row -> teamDto(row[TeamsTable.id])!! },
            individuals = IndividualsTable.selectAll()
                .where { IndividualsTable.registrationId eq regId }
                .orderBy(IndividualsTable.name)
                .map { it.toIndividual() },
            submittedAt = this[RegistrationsTable.submittedAtEpochMs]?.let { Instant.ofEpochMilli(it).toString() },
            paidAt = this[RegistrationsTable.paidAtEpochMs]?.let { Instant.ofEpochMilli(it).toString() },
        )
    }
}
