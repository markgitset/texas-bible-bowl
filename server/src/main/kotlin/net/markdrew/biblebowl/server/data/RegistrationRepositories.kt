package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.AwayMemberDto
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.GuestDto
import net.markdrew.biblebowl.api.congregationCodeCandidates
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.SeedMemberDto
import net.markdrew.biblebowl.api.graduationYearFor
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.neq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The maximum contestants per team (competition rule; enforced at the repository layer). */
const val MAX_TEAM_SIZE = 4

/** JSON codec for the guests' volunteer-position lists (stored as a JSON array of strings). */
private val positionsJson = Json { ignoreUnknownKeys = true }

internal fun encodePositions(positions: List<String>): String = positionsJson.encodeToString(positions)

internal fun decodePositions(raw: String): List<String> =
    runCatching { positionsJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

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

/** Outcome of (re)assigning a roster entry to a team (or unassigning it), cap enforced in-transaction. */
sealed interface AssignResult {
    data object Assigned : AssignResult
    data object RosterFull : AssignResult
    /** No such team, or the team belongs to a different season than the member's registration. */
    data object TeamNotFound : AssignResult
    data object MemberNotFound : AssignResult
}

/** Outcome of enrolling a returning contestant into the current season. */
sealed interface EnrollResult {
    data object Enrolled : EnrollResult
    /** No such contestant in this congregation. */
    data object ContestantNotFound : EnrollResult
    /** The contestant already has a roster entry this season. */
    data object AlreadyEnrolled : EnrollResult
    /** The target team doesn't exist or belongs to another registration. */
    data object TeamNotFound : EnrollResult
    data object RosterFull : EnrollResult
    /** A workbook-seeded youth (grade, no birthdate) needs a birthdate at first enrollment. */
    data object BirthdateRequired : EnrollResult
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
    /** Deletes a team, freeing its members to the unassigned pool (they are kept, not deleted). */
    fun deleteTeam(teamId: String): Boolean
    fun addMember(teamId: String, req: UpsertRosterEntryRequest): AddMemberResult
    fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto?
    fun deleteMember(memberId: String): Boolean
    /**
     * Moves a member to [teamId] (≤4), or frees it to the pool when null. The team may belong to
     * another congregation's registration in the same season (a combo team) — the ROUTE gates that
     * on an event-wide grant; here only the season must match.
     */
    fun assignMemberToTeam(memberId: String, teamId: String?): AssignResult
    /** Adds an individual (adult) contestant, creating the draft registration if needed. */
    fun addIndividual(congregationId: String, seasonYear: String, req: UpsertIndividualRequest): RosterEntryDto
    fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto?
    fun deleteIndividual(individualId: String): Boolean
    /** Adds a registered guest (paying non-contestant), creating the draft registration if needed. */
    fun addGuest(congregationId: String, seasonYear: String, req: UpsertGuestRequest): GuestDto
    fun updateGuest(guestId: String, req: UpsertGuestRequest): GuestDto?
    fun deleteGuest(guestId: String): Boolean
    /**
     * Pins the registration to the event site [siteId] (a season [net.markdrew.biblebowl.api.EventSiteDto] id),
     * creating the draft registration if needed — site choice is part of the congregation step, before
     * any team exists. Validation that the id is a real current-season site is the route's.
     */
    fun setSite(congregationId: String, seasonYear: String, siteId: String): RegistrationDto
    /** Marks the registration SUBMITTED (idempotent; re-submit refreshes the timestamp). */
    fun submit(congregationId: String, seasonYear: String): RegistrationDto?
    /** Scoping lookups: which congregation a team/member/individual belongs to (for permission checks). */
    fun congregationIdForTeam(teamId: String): String?
    fun congregationIdForMember(memberId: String): String?
    fun congregationIdForIndividual(individualId: String): String?
    fun congregationIdForGuest(guestId: String): String?
    /** The durable contestant a roster entry belongs to; null for an unlinked legacy row. */
    fun contestantIdForMember(memberId: String): String?
    /**
     * Durable contestants of [congregationId] with no roster entry in [seasonYear] — returning
     * *candidates*, offered for one-click enrollment. Youth-eligibility filtering (division) is the
     * caller's, since it needs the season; here they're returned name-sorted with last-seen details.
     */
    fun returningContestants(congregationId: String, seasonYear: String): List<ReturningContestantDto>
    /**
     * Creates [seasonYear]'s roster entry for an existing contestant (on [teamId], else unassigned).
     * [birthdate] is for workbook-seeded youth (grade but no birthdate): required for them —
     * [EnrollResult.BirthdateRequired] otherwise — and written onto the durable contestant, who is
     * a normal birthdate-carrying youth from then on. Ignored for everyone else.
     */
    fun enrollContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
        shirtSize: ShirtSize,
        teamId: String?,
        birthdate: String? = null,
    ): EnrollResult
    /**
     * Workbook seed (item 17, F13): upserts the durable contestant for `(congregationId, name)` —
     * grade-seeded, no birthdate ([SeedMemberDto.grade] becomes a graduation year) — plus their
     * [seasonYear] enrollment on [teamId] (null = unassigned). Idempotent: an existing enrollment
     * is updated (shirt size, team) rather than duplicated, and a contestant who already carries a
     * birthdate keeps it (the seed only fills identity gaps). Null when the team doesn't exist.
     */
    fun seedMember(congregationId: String, seasonYear: String, teamId: String?, member: SeedMemberDto): RosterEntryDto?
    /** Every registration for [seasonYear], with full teams/rosters (registration desk). */
    fun listForSeason(seasonYear: String): List<RegistrationDto>
    /** Every season year with at least one registration (any status) — the desk's year picker. */
    fun seasonYears(): List<String>
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
                phone = req.phone.trim(),
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
            phone = req.phone.trim(),
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
        var siteId: String? = null,
        val teamIds: MutableList<String> = mutableListOf(),
        val individualIds: MutableList<String> = mutableListOf(),
    )

    private data class Team(val id: String, val regId: String, var name: String, val memberIds: MutableList<String> = mutableListOf())

    /** A durable contestant (person), reused across seasons; see [ContestantsTable]. */
    private data class Contestant(
        val id: String,
        val congregationId: String,
        var name: String,
        // Mutable for exactly one transition: a workbook-seeded youth gets their real birthdate
        // at first enrollment (see RegistrationRepository.enrollContestant).
        var birthdate: String?,
        var gender: Gender?,
        var firstSeasonYear: String?,
        /** Seeded grade as a graduation year (see [ContestantsTable.graduationYear]); birthdate wins. */
        var graduationYear: Int? = null,
    )

    private val regs = mutableMapOf<String, Reg>() // key = "$congregationId|$seasonYear"
    private val teams = mutableMapOf<String, Team>()
    private val members = mutableMapOf<String, RosterEntryDto>()
    private val memberTeam = mutableMapOf<String, String>() // member id -> team id (absent = unassigned)
    private val memberReg = mutableMapOf<String, String>() // member id -> registration id (always set)
    private val contestants = mutableMapOf<String, Contestant>() // durable person id -> contestant
    private val memberContestant = mutableMapOf<String, String>() // team member id -> contestant id
    private val individualContestant = mutableMapOf<String, String>() // individual id -> contestant id
    private val individuals = mutableMapOf<String, RosterEntryDto>()
    private val individualReg = mutableMapOf<String, String>()
    private val guests = mutableMapOf<String, GuestDto>()
    private val guestReg = mutableMapOf<String, String>()
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
        // Keep the members: drop their team link so they land in the unassigned pool.
        team.memberIds.forEach { memberTeam.remove(it) }
        regs.values.forEach { it.teamIds.remove(teamId) }
        true
    }

    override fun addMember(teamId: String, req: UpsertRosterEntryRequest): AddMemberResult = synchronized(lock) {
        val team = teams[teamId] ?: return AddMemberResult.TeamNotFound
        if (team.memberIds.size >= MAX_TEAM_SIZE) return AddMemberResult.RosterFull
        val reg = regs.values.first { it.id == team.regId }
        val code = generateSequence { ClaimCodes.generate() }.first { usedCodes.add(it) }
        val (contestantId, firstYear) = findOrCreateContestant(reg.congregationId, req, reg.seasonYear)
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            birthdate = req.birthdate,
            shirtSize = req.shirtSize,
            gender = req.gender,
            firstSeasonYear = firstYear,
            claimCode = code,
        )
        members[entry.id] = entry
        memberTeam[entry.id] = teamId
        memberReg[entry.id] = reg.id
        memberContestant[entry.id] = contestantId
        // A returning contestant re-added by name inherits their existing owner (claim persists).
        ownerForContestant(contestantId)?.let { entryOwner[entry.id] = it }
        team.memberIds += entry.id
        AddMemberResult.Added(entry)
    }

    /** True when [contestantId] has an enrollment in a season earlier than [seasonYear]. */
    private fun contestantHasPriorSeason(contestantId: String, seasonYear: String): Boolean =
        memberContestant.filterValues { it == contestantId }.keys.any { mid ->
            memberReg[mid]?.let { regId -> regs.values.firstOrNull { it.id == regId }?.seasonYear }
                ?.let { it < seasonYear } == true
        }

    /**
     * Finds the durable contestant for `(congregation, name, birthdate)` — reused across seasons, so
     * re-adding last year's contestant recognizes the same person — or creates one, refreshing name
     * and gender. Returns the contestant id and the resolved first season year: locked to the earliest
     * once the person has competed before, else this season when the coach marked them inexperienced.
     */
    private fun findOrCreateContestant(
        congregationId: String,
        req: UpsertRosterEntryRequest,
        seasonYear: String,
    ): Pair<String, String?> {
        val trimmed = req.name.trim()
        val existing = contestants.values.firstOrNull {
            it.congregationId == congregationId &&
                it.name.equals(trimmed, ignoreCase = true) &&
                it.birthdate == req.birthdate
        } ?: contestants.values.firstOrNull {
            // A workbook-seeded youth (no birthdate yet) re-added by name WITH a birthdate is the
            // same person — adopt the birthdate instead of creating a duplicate.
            it.congregationId == congregationId && it.name.equals(trimmed, ignoreCase = true) &&
                it.birthdate == null && it.graduationYear != null
        }?.also { it.birthdate = req.birthdate }
        if (existing != null) {
            val firstYear = if (contestantHasPriorSeason(existing.id, seasonYear)) existing.firstSeasonYear
                else seasonYear.takeIf { req.inexperienced }
            existing.name = trimmed
            existing.gender = req.gender
            existing.firstSeasonYear = firstYear
            return existing.id to firstYear
        }
        val firstYear = seasonYear.takeIf { req.inexperienced }
        val contestant = Contestant(UUID.randomUUID().toString(), congregationId, trimmed, req.birthdate, req.gender, firstYear)
        contestants[contestant.id] = contestant
        return contestant.id to firstYear
    }

    override fun assignMemberToTeam(memberId: String, teamId: String?): AssignResult = synchronized(lock) {
        members[memberId] ?: return AssignResult.MemberNotFound
        val regId = memberReg[memberId] ?: return AssignResult.MemberNotFound
        val current = memberTeam[memberId]
        if (teamId == null) {
            current?.let { teams[it]?.memberIds?.remove(memberId) }
            memberTeam.remove(memberId)
            return AssignResult.Assigned
        }
        // Combo teams: any team in the member's season qualifies, own congregation's or not.
        val memberSeason = regs.values.firstOrNull { it.id == regId }?.seasonYear
        val team = teams[teamId]
            ?.takeIf { t -> regs.values.firstOrNull { it.id == t.regId }?.seasonYear == memberSeason }
            ?: return AssignResult.TeamNotFound
        if (current == teamId) return AssignResult.Assigned // already there
        if (team.memberIds.size >= MAX_TEAM_SIZE) return AssignResult.RosterFull
        current?.let { teams[it]?.memberIds?.remove(memberId) }
        team.memberIds += memberId
        memberTeam[memberId] = teamId
        AssignResult.Assigned
    }

    override fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto? = synchronized(lock) {
        val entry = members[memberId] ?: return null
        val reg = memberReg[memberId]?.let { regId -> regs.values.firstOrNull { it.id == regId } }
            ?: return entry
        val (contestantId, firstYear) = findOrCreateContestant(reg.congregationId, req, reg.seasonYear)
        members[memberId] = entry.copy(
            name = req.name.trim(),
            birthdate = req.birthdate,
            shirtSize = req.shirtSize,
            gender = req.gender,
            firstSeasonYear = firstYear,
        )
        // Re-point at the (possibly different) durable contestant — the person may have been renamed
        // or had their birthdate corrected — and prune the old one if nothing else references it.
        val previous = memberContestant[memberId]
        memberContestant[memberId] = contestantId
        previous?.takeIf { it != contestantId }?.let { pruneContestantIfOrphaned(it) }
        memberDto(memberId)
    }

    /** Drops a durable contestant once no roster entry (team or individual, any season) references it. */
    private fun pruneContestantIfOrphaned(contestantId: String) {
        val used = memberContestant.values.any { it == contestantId } ||
            individualContestant.values.any { it == contestantId }
        if (!used) contestants.remove(contestantId)
    }

    override fun deleteMember(memberId: String): Boolean = synchronized(lock) {
        val entry = members.remove(memberId) ?: return false
        usedCodes.remove(entry.claimCode)
        memberTeam.remove(memberId)
        memberReg.remove(memberId)
        val contestantId = memberContestant.remove(memberId)
        teams.values.forEach { it.memberIds.remove(memberId) }
        contestantId?.let { pruneContestantIfOrphaned(it) }
        true
    }

    override fun addIndividual(
        congregationId: String,
        seasonYear: String,
        req: UpsertIndividualRequest,
    ): RosterEntryDto = synchronized(lock) {
        val reg = regFor(congregationId, seasonYear)
        val code = generateSequence { ClaimCodes.generate() }.first { usedCodes.add(it) }
        val contestantId = findOrCreateAdultContestant(congregationId, req)
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            birthdate = null,
            shirtSize = req.shirtSize,
            gender = req.gender,
            claimCode = code,
            tribeLeaderWilling = req.tribeLeaderWilling,
        )
        individuals[entry.id] = entry
        individualReg[entry.id] = reg.id
        individualContestant[entry.id] = contestantId
        // A returning adult re-added by name inherits their existing owner (claim persists).
        ownerForContestant(contestantId)?.let { entryOwner[entry.id] = it }
        reg.individualIds += entry.id
        individualDto(entry.id)!!
    }

    /** Finds or creates the birthdate-less durable contestant for an adult `(congregation, name)`. */
    private fun findOrCreateAdultContestant(congregationId: String, req: UpsertIndividualRequest): String {
        val trimmed = req.name.trim()
        val existing = contestants.values.firstOrNull {
            // graduationYear == null: a workbook-seeded youth is also birthdate-less but is NOT
            // this adult (the seed marks youth with a graduation year).
            it.congregationId == congregationId && it.name.equals(trimmed, ignoreCase = true) &&
                it.birthdate == null && it.graduationYear == null
        }
        if (existing != null) {
            existing.name = trimmed
            existing.gender = req.gender
            return existing.id
        }
        val contestant = Contestant(UUID.randomUUID().toString(), congregationId, trimmed, null, req.gender, null)
        contestants[contestant.id] = contestant
        return contestant.id
    }

    override fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto? =
        synchronized(lock) {
            val entry = individuals[individualId] ?: return null
            individuals[individualId] = entry.copy(
                name = req.name.trim(), shirtSize = req.shirtSize, gender = req.gender,
                tribeLeaderWilling = req.tribeLeaderWilling,
            )
            val congregationId = congregationIdForIndividual(individualId)
            if (congregationId != null) {
                val previous = individualContestant[individualId]
                val contestantId = findOrCreateAdultContestant(congregationId, req)
                individualContestant[individualId] = contestantId
                previous?.takeIf { it != contestantId }?.let { pruneContestantIfOrphaned(it) }
            }
            individualDto(individualId)
        }

    override fun deleteIndividual(individualId: String): Boolean = synchronized(lock) {
        val entry = individuals.remove(individualId) ?: return false
        usedCodes.remove(entry.claimCode)
        individualReg.remove(individualId)
        val contestantId = individualContestant.remove(individualId)
        regs.values.forEach { it.individualIds.remove(individualId) }
        contestantId?.let { pruneContestantIfOrphaned(it) }
        true
    }

    override fun addGuest(congregationId: String, seasonYear: String, req: UpsertGuestRequest): GuestDto =
        synchronized(lock) {
            val reg = regFor(congregationId, seasonYear)
            val guest = GuestDto(
                UUID.randomUUID().toString(), req.name.trim(), req.shirtSize, req.birthdate, req.gender,
                positions = req.positions, tribeLeaderWilling = req.tribeLeaderWilling, contact = req.contact,
            )
            guests[guest.id] = guest
            guestReg[guest.id] = reg.id
            guest
        }

    override fun updateGuest(guestId: String, req: UpsertGuestRequest): GuestDto? = synchronized(lock) {
        val guest = guests[guestId] ?: return null
        guests[guestId] = guest.copy(
            name = req.name.trim(), shirtSize = req.shirtSize, birthdate = req.birthdate, gender = req.gender,
            positions = req.positions, tribeLeaderWilling = req.tribeLeaderWilling, contact = req.contact,
        )
        guests[guestId]
    }

    override fun deleteGuest(guestId: String): Boolean = synchronized(lock) {
        guestReg.remove(guestId)
        guests.remove(guestId) != null
    }

    override fun setSite(congregationId: String, seasonYear: String, siteId: String): RegistrationDto =
        synchronized(lock) {
            val reg = regFor(congregationId, seasonYear)
            reg.siteId = siteId
            reg.toDto()
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
        // Via the registration link, so it resolves even for an unassigned (teamless) member.
        memberReg[memberId]?.let { regId -> regs.values.firstOrNull { it.id == regId }?.congregationId }
    }

    override fun congregationIdForIndividual(individualId: String): String? = synchronized(lock) {
        individualReg[individualId]?.let { regId -> regs.values.firstOrNull { it.id == regId }?.congregationId }
    }

    override fun congregationIdForGuest(guestId: String): String? = synchronized(lock) {
        guestReg[guestId]?.let { regId -> regs.values.firstOrNull { it.id == regId }?.congregationId }
    }

    override fun contestantIdForMember(memberId: String): String? = synchronized(lock) {
        memberContestant[memberId]
    }

    override fun returningContestants(congregationId: String, seasonYear: String): List<ReturningContestantDto> =
        synchronized(lock) {
            val seasonRegId = regs["$congregationId|$seasonYear"]?.id
            // Enrolled this season = has a team OR individual entry in this season's registration.
            val enrolledThisSeason = (memberContestant.filterKeys { memberReg[it] == seasonRegId } +
                individualContestant.filterKeys { individualReg[it] == seasonRegId }).values.toSet()
            contestants.values
                .filter { it.congregationId == congregationId && it.id !in enrolledThisSeason }
                .sortedBy { it.name.lowercase() }
                .map { contestant ->
                    // Most recent enrollment of this person (team for youth, individual for adults).
                    val recent = (memberContestant.filterValues { it == contestant.id }.keys
                        .mapNotNull { id -> members[id]?.shirtSize?.let { memberReg[id] to it } } +
                        individualContestant.filterValues { it == contestant.id }.keys
                            .mapNotNull { id -> individuals[id]?.shirtSize?.let { individualReg[id] to it } })
                        .mapNotNull { (regId, shirt) -> regs.values.firstOrNull { it.id == regId }?.let { it.seasonYear to shirt } }
                        .maxByOrNull { it.first }
                    ReturningContestantDto(
                        contestantId = contestant.id,
                        name = contestant.name,
                        birthdate = contestant.birthdate,
                        gender = contestant.gender,
                        lastSeasonYear = recent?.first,
                        lastShirtSize = recent?.second,
                        firstSeasonYear = contestant.firstSeasonYear,
                        graduationYear = contestant.graduationYear,
                    )
                }
        }

    override fun enrollContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
        shirtSize: ShirtSize,
        teamId: String?,
        birthdate: String?,
    ): EnrollResult = synchronized(lock) {
        val contestant = contestants[contestantId]?.takeIf { it.congregationId == congregationId }
            ?: return EnrollResult.ContestantNotFound
        // A workbook-seeded youth finally gets their real birthdate here (route validates the grade).
        if (contestant.birthdate == null && contestant.graduationYear != null) {
            contestant.birthdate = birthdate ?: return EnrollResult.BirthdateRequired
        }
        val reg = regFor(congregationId, seasonYear)
        val code = generateSequence { ClaimCodes.generate() }.first { usedCodes.add(it) }
        // Adults (birthdate-less) enroll as individuals; youth as team members (optionally on a team).
        if (contestant.birthdate == null) {
            val already = individualContestant.any { (iid, cid) -> cid == contestantId && individualReg[iid] == reg.id }
            if (already) return EnrollResult.AlreadyEnrolled
            val entry = RosterEntryDto(
                id = UUID.randomUUID().toString(), name = contestant.name, birthdate = null,
                shirtSize = shirtSize, gender = contestant.gender, claimCode = code,
            )
            individuals[entry.id] = entry
            individualReg[entry.id] = reg.id
            individualContestant[entry.id] = contestantId
            ownerForContestant(contestantId)?.let { entryOwner[entry.id] = it }
            reg.individualIds += entry.id
            return EnrollResult.Enrolled
        }
        val alreadyEnrolled = memberContestant.any { (memberId, cid) -> cid == contestantId && memberReg[memberId] == reg.id }
        if (alreadyEnrolled) return EnrollResult.AlreadyEnrolled
        val team = teamId?.let { teams[it]?.takeIf { t -> t.regId == reg.id } ?: return EnrollResult.TeamNotFound }
        if (team != null && team.memberIds.size >= MAX_TEAM_SIZE) return EnrollResult.RosterFull
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = contestant.name,
            birthdate = contestant.birthdate,
            shirtSize = shirtSize,
            gender = contestant.gender,
            firstSeasonYear = contestant.firstSeasonYear,
            claimCode = code,
        )
        members[entry.id] = entry
        memberReg[entry.id] = reg.id
        memberContestant[entry.id] = contestantId
        // Enrolling a returning contestant carries their existing owner forward (claim persists).
        ownerForContestant(contestantId)?.let { entryOwner[entry.id] = it }
        if (team != null) {
            memberTeam[entry.id] = team.id
            team.memberIds += entry.id
        }
        EnrollResult.Enrolled
    }

    override fun seedMember(
        congregationId: String,
        seasonYear: String,
        teamId: String?,
        member: SeedMemberDto,
    ): RosterEntryDto? = synchronized(lock) {
        val reg = regFor(congregationId, seasonYear)
        // Combo rule (item 5): the team may belong to another congregation's same-season
        // registration — the member stays registered (and billed) by their own congregation.
        val team = teamId?.let {
            teams[it]?.takeIf { t -> regs.values.firstOrNull { r -> r.id == t.regId }?.seasonYear == seasonYear }
                ?: return null
        }
        val trimmed = member.name.trim()
        val contestant = contestants.values.firstOrNull {
            it.congregationId == congregationId && it.name.equals(trimmed, ignoreCase = true)
        } ?: Contestant(UUID.randomUUID().toString(), congregationId, trimmed, null, member.gender, null)
            .also { contestants[it.id] = it }
        // The seed fills identity gaps but never overwrites curated data: birthdate wins over the
        // seeded grade, and an inexperienced seed can only pull the first season earlier.
        if (contestant.gender == null) contestant.gender = member.gender
        if (contestant.birthdate == null) contestant.graduationYear = graduationYearFor(seasonYear, member.grade)
        if (member.inexperienced) {
            contestant.firstSeasonYear = listOfNotNull(contestant.firstSeasonYear, seasonYear).min()
        }
        val existingId = memberContestant.entries
            .firstOrNull { (mid, cid) -> cid == contestant.id && memberReg[mid] == reg.id }?.key
        val entryId: String
        if (existingId == null) {
            entryId = UUID.randomUUID().toString()
            val code = generateSequence { ClaimCodes.generate() }.first { usedCodes.add(it) }
            members[entryId] = RosterEntryDto(
                id = entryId, name = trimmed, birthdate = contestant.birthdate,
                shirtSize = member.shirtSize, gender = contestant.gender,
                firstSeasonYear = contestant.firstSeasonYear, claimCode = code,
            )
            memberReg[entryId] = reg.id
            memberContestant[entryId] = contestant.id
        } else {
            entryId = existingId
            members[entryId] = members[entryId]!!
                .copy(shirtSize = member.shirtSize, firstSeasonYear = contestant.firstSeasonYear)
        }
        // (Re)place on the team when there's room; a full team leaves the entry unassigned.
        val current = memberTeam[entryId]
        if (team != null && current != team.id && team.memberIds.size < MAX_TEAM_SIZE) {
            current?.let { teams[it]?.memberIds?.remove(entryId) }
            team.memberIds += entryId
            memberTeam[entryId] = team.id
        }
        memberDto(entryId)
    }

    override fun listForSeason(seasonYear: String): List<RegistrationDto> = synchronized(lock) {
        regs.values.filter { it.seasonYear == seasonYear }.map { it.toDto() }
    }

    override fun seasonYears(): List<String> = synchronized(lock) {
        regs.values.map { it.seasonYear }.distinct()
    }

    override fun setPaid(registrationId: String, paidAtEpochMs: Long?): RegistrationDto? = synchronized(lock) {
        val reg = regs.values.firstOrNull { it.id == registrationId } ?: return null
        reg.paidAtMs = paidAtEpochMs
        reg.toDto()
    }

    override fun claimEntry(code: String, userId: String): ClaimResult = synchronized(lock) {
        val entry = (members.values + individuals.values).firstOrNull { it.claimCode == code }
            ?: return ClaimResult.NotFound
        // Ownership is durable per person: claiming any of a contestant's entries owns them all
        // (across seasons), so an account claims once and future enrollments are theirs automatically.
        val owned: Set<String> = when {
            entry.id in members ->
                memberContestant[entry.id]?.let { cid -> memberContestant.filterValues { it == cid }.keys }
            else ->
                individualContestant[entry.id]?.let { cid -> individualContestant.filterValues { it == cid }.keys }
        } ?: setOf(entry.id) // an unlinked legacy row
        val existingOwner = owned.firstNotNullOfOrNull { entryOwner[it] }
        if (existingOwner != null && existingOwner != userId) return ClaimResult.AlreadyClaimed
        owned.forEach { entryOwner[it] = userId }
        ClaimResult.Claimed(entry.copy(claimed = true))
    }

    override fun entryIdsOwnedBy(userId: String): Set<String> = synchronized(lock) {
        entryOwner.filterValues { it == userId }.keys.toSet()
    }

    /** The account that owns [contestantId], via any of its entries (team or individual), or null. */
    private fun ownerForContestant(contestantId: String): String? =
        (memberContestant + individualContestant).entries
            .firstOrNull { (eid, cid) -> cid == contestantId && entryOwner[eid] != null }?.let { entryOwner[it.key] }

    /** A team roster entry with its identity sourced from the durable contestant and ownership resolved. */
    private fun memberDto(id: String): RosterEntryDto? {
        val entry = members[id] ?: return null
        val contestant = memberContestant[id]?.let { contestants[it] }
        return entry.copy(
            name = contestant?.name ?: entry.name,
            birthdate = contestant?.birthdate ?: entry.birthdate,
            gender = contestant?.gender ?: entry.gender,
            firstSeasonYear = if (contestant != null) contestant.firstSeasonYear else entry.firstSeasonYear,
            claimed = id in entryOwner,
        )
    }

    /** An individual (adult) entry with its identity sourced from the durable contestant and claimed resolved. */
    private fun individualDto(id: String): RosterEntryDto? {
        val entry = individuals[id] ?: return null
        val contestant = individualContestant[id]?.let { contestants[it] }
        return entry.copy(
            name = contestant?.name ?: entry.name,
            gender = contestant?.gender ?: entry.gender,
            claimed = id in entryOwner,
        )
    }

    /** The congregation a registration row belongs to, for labeling cross-congregation members. */
    private fun congregationNameForReg(regId: String): String =
        regs.values.firstOrNull { it.id == regId }?.let { congregations.findById(it.congregationId)?.name } ?: "?"

    private fun Team.toDto() = TeamDto(id, name, memberIds.mapNotNull { mid ->
        memberDto(mid)?.let { entry ->
            val homeRegId = memberReg[mid]
            // A visiting (combo-team) member carries their own congregation for display/billing.
            if (homeRegId == null || homeRegId == regId) entry
            else entry.copy(
                congregationId = regs.values.firstOrNull { it.id == homeRegId }?.congregationId,
                congregationName = congregationNameForReg(homeRegId),
            )
        }
    })

    private fun Reg.toDto(): RegistrationDto = RegistrationDto(
        id = id,
        congregation = congregations.findById(congregationId)
            ?: CongregationDto(congregationId, "?", "?"),
        seasonYear = seasonYear,
        status = status,
        siteId = siteId,
        teams = teamIds.mapNotNull { teams[it]?.toDto() },
        individuals = individualIds.mapNotNull { individualDto(it) },
        unassigned = members.keys
            .filter { memberReg[it] == id && it !in memberTeam }
            .mapNotNull { memberDto(it) }
            .sortedBy { it.name.lowercase() },
        awayMembers = members.keys
            .filter { mid -> memberReg[mid] == id && memberTeam[mid]?.let { teams[it]?.regId != id } == true }
            .mapNotNull { mid ->
                val team = teams[memberTeam[mid]] ?: return@mapNotNull null
                memberDto(mid)?.let {
                    AwayMemberDto(it, team.id, team.name, congregationNameForReg(team.regId))
                }
            }
            .sortedBy { it.entry.name.lowercase() },
        guests = guests.values
            .filter { guestReg[it.id] == id }
            .sortedBy { it.name.lowercase() },
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
            it[phone] = req.phone.trim()
            it[CongregationsTable.code] = code
            it[CongregationsTable.createdByUserId] = createdByUserId
            it[createdAtEpochMs] = System.currentTimeMillis()
        }
        CreateCongregationResult.Created(
            CongregationDto(newId, n, c, state, req.mailingAddress.trim(), req.zip.trim(), req.phone.trim(), code),
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
            it[phone] = req.phone.trim()
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
        phone = this[CongregationsTable.phone],
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
        // Keep the members: null out their team link so they land in the unassigned pool.
        TeamMembersTable.update({ TeamMembersTable.teamId eq teamId }) { it[TeamMembersTable.teamId] = null }
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
        val regId = team[TeamsTable.registrationId]
        val (contestant, firstYear) = findOrCreateContestant(congregationIdFor(regId), req, seasonYearForReg(regId))
        val code = freshClaimCode()
        val memberId = UUID.randomUUID().toString()
        TeamMembersTable.insert {
            it[id] = memberId
            it[TeamMembersTable.teamId] = teamId
            it[registrationId] = regId
            it[contestantId] = contestant
            it[shirtSize] = req.shirtSize.name
            it[claimCode] = code
            // A returning contestant re-added by name inherits their existing owner (claim persists).
            it[ownerUserId] = ownerForContestant(contestant)
        }
        AddMemberResult.Added(
            RosterEntryDto(memberId, req.name.trim(), req.birthdate, req.shirtSize, req.gender, firstYear, code)
        )
    }

    private fun regRowById(regId: String): ResultRow =
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }.single()

    private fun congregationIdFor(regId: String): String = regRowById(regId)[RegistrationsTable.congregationId]
    private fun seasonYearForReg(regId: String): String = regRowById(regId)[RegistrationsTable.seasonYear]

    /**
     * Finds the durable contestant for `(congregation, name, birthdate)` — reused across seasons, so
     * re-adding last year's contestant recognizes the same person — or creates one, refreshing name and
     * gender. Returns the contestant id and the resolved first season year: locked to the earliest once
     * the person has competed before, else this season when the coach marked them inexperienced. The
     * contestant is the source of truth, so the value is written there, not on the enrollment.
     */
    private fun findOrCreateContestant(
        congregationId: String,
        req: UpsertRosterEntryRequest,
        seasonYear: String,
    ): Pair<String, String?> {
        val trimmed = req.name.trim()
        val existing = ContestantsTable.selectAll()
            .where {
                (ContestantsTable.congregationId eq congregationId) and
                    (ContestantsTable.name.lowerCase() eq trimmed.lowercase()) and
                    (req.birthdate?.let { ContestantsTable.birthdate eq it } ?: ContestantsTable.birthdate.isNull())
            }
            .firstOrNull()
            ?: ContestantsTable.selectAll()
                .where {
                    // A workbook-seeded youth (no birthdate yet) re-added by name WITH a birthdate
                    // is the same person — adopt the birthdate instead of creating a duplicate.
                    (ContestantsTable.congregationId eq congregationId) and
                        (ContestantsTable.name.lowerCase() eq trimmed.lowercase()) and
                        ContestantsTable.birthdate.isNull() and ContestantsTable.graduationYear.isNotNull()
                }
                .firstOrNull()
                ?.also { seeded ->
                    ContestantsTable.update({ ContestantsTable.id eq seeded[ContestantsTable.id] }) {
                        it[birthdate] = req.birthdate
                    }
                }
        if (existing != null) {
            val existingId = existing[ContestantsTable.id]
            val hasPriorSeason = (TeamMembersTable innerJoin RegistrationsTable).selectAll()
                .where { (TeamMembersTable.contestantId eq existingId) and (RegistrationsTable.seasonYear less seasonYear) }
                .any()
            val firstYear = if (hasPriorSeason) existing[ContestantsTable.firstSeasonYear]
                else seasonYear.takeIf { req.inexperienced }
            ContestantsTable.update({ ContestantsTable.id eq existingId }) {
                it[name] = trimmed
                it[gender] = req.gender.name
                it[ContestantsTable.firstSeasonYear] = firstYear
            }
            return existingId to firstYear
        }
        val newId = UUID.randomUUID().toString()
        val firstYear = seasonYear.takeIf { req.inexperienced }
        ContestantsTable.insert {
            it[id] = newId
            it[ContestantsTable.congregationId] = congregationId
            it[name] = trimmed
            it[birthdate] = req.birthdate
            it[gender] = req.gender.name
            it[ContestantsTable.firstSeasonYear] = firstYear
        }
        return newId to firstYear
    }

    /** Drops a durable contestant once no roster entry (team or individual, any season) references it. */
    private fun pruneContestantIfOrphaned(contestantId: String) {
        val stillUsed = TeamMembersTable.selectAll().where { TeamMembersTable.contestantId eq contestantId }.any() ||
            IndividualsTable.selectAll().where { IndividualsTable.contestantId eq contestantId }.any()
        if (!stillUsed) ContestantsTable.deleteWhere { ContestantsTable.id eq contestantId }
    }

    /** The account that owns [contestantId], via any of its entries (team or individual), or null. */
    private fun ownerForContestant(contestantId: String): String? =
        TeamMembersTable.selectAll().where { TeamMembersTable.contestantId eq contestantId }
            .mapNotNull { it[TeamMembersTable.ownerUserId] }.firstOrNull()
            ?: IndividualsTable.selectAll().where { IndividualsTable.contestantId eq contestantId }
                .mapNotNull { it[IndividualsTable.ownerUserId] }.firstOrNull()

    /**
     * Finds the durable adult contestant for `(congregation, name)` — birthdate-less, reused across
     * seasons — or creates one, refreshing name and gender. Adults have no division/experience.
     */
    private fun findOrCreateAdultContestant(congregationId: String, req: UpsertIndividualRequest): String {
        val trimmed = req.name.trim()
        val existing = ContestantsTable.selectAll()
            .where {
                // graduation_year null: a workbook-seeded youth is also birthdate-less but is NOT
                // this adult (the seed marks youth with a graduation year).
                (ContestantsTable.congregationId eq congregationId) and
                    (ContestantsTable.name.lowerCase() eq trimmed.lowercase()) and
                    ContestantsTable.birthdate.isNull() and ContestantsTable.graduationYear.isNull()
            }
            .firstOrNull()
        if (existing != null) {
            val existingId = existing[ContestantsTable.id]
            ContestantsTable.update({ ContestantsTable.id eq existingId }) {
                it[name] = trimmed
                it[gender] = req.gender.name
            }
            return existingId
        }
        val newId = UUID.randomUUID().toString()
        ContestantsTable.insert {
            it[id] = newId
            it[ContestantsTable.congregationId] = congregationId
            it[name] = trimmed
            it[birthdate] = null
            it[gender] = req.gender.name
            it[ContestantsTable.firstSeasonYear] = null
        }
        return newId
    }

    /** Reads one enrollment as a [RosterEntryDto], joining the contestant for its identity. */
    private fun memberEntry(memberId: String): RosterEntryDto? =
        (TeamMembersTable innerJoin ContestantsTable).selectAll()
            .where { TeamMembersTable.id eq memberId }.singleOrNull()?.toEntry()

    override fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto? = transaction(db) {
        val row = TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }.singleOrNull()
            ?: return@transaction null
        val regId = row[TeamMembersTable.registrationId]
        val previousContestant = row[TeamMembersTable.contestantId]
        val (contestant, _) = findOrCreateContestant(congregationIdFor(regId), req, seasonYearForReg(regId))
        TeamMembersTable.update({ TeamMembersTable.id eq memberId }) {
            it[shirtSize] = req.shirtSize.name
            it[contestantId] = contestant
        }
        // The person may have been renamed / had a birthdate fixed — prune the old contestant if it's
        // now unreferenced.
        if (previousContestant != null && previousContestant != contestant) pruneContestantIfOrphaned(previousContestant)
        memberEntry(memberId)
    }

    override fun deleteMember(memberId: String): Boolean = transaction(db) {
        val contestantId = TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }
            .singleOrNull()?.get(TeamMembersTable.contestantId)
        val deleted = TeamMembersTable.deleteWhere { TeamMembersTable.id eq memberId } > 0
        if (deleted && contestantId != null) pruneContestantIfOrphaned(contestantId)
        deleted
    }

    override fun assignMemberToTeam(memberId: String, teamId: String?): AssignResult = transaction(db) {
        val member = TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }.singleOrNull()
            ?: return@transaction AssignResult.MemberNotFound
        val current = member[TeamMembersTable.teamId]
        if (teamId == null) {
            if (current != null) {
                TeamMembersTable.update({ TeamMembersTable.id eq memberId }) { it[TeamMembersTable.teamId] = null }
            }
            return@transaction AssignResult.Assigned
        }
        if (current == teamId) return@transaction AssignResult.Assigned // already there
        val team = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
            ?: return@transaction AssignResult.TeamNotFound
        // Combo teams: any team in the member's season qualifies, own congregation's or not.
        val sameSeason = seasonYearForReg(team[TeamsTable.registrationId]) ==
            seasonYearForReg(member[TeamMembersTable.registrationId])
        if (!sameSeason) return@transaction AssignResult.TeamNotFound
        val size = TeamMembersTable.selectAll().where { TeamMembersTable.teamId eq teamId }.count()
        if (size >= MAX_TEAM_SIZE) return@transaction AssignResult.RosterFull
        TeamMembersTable.update({ TeamMembersTable.id eq memberId }) { it[TeamMembersTable.teamId] = teamId }
        AssignResult.Assigned
    }

    override fun addIndividual(
        congregationId: String,
        seasonYear: String,
        req: UpsertIndividualRequest,
    ): RosterEntryDto = transaction(db) {
        val regId = regIdFor(congregationId, seasonYear)
        val contestant = findOrCreateAdultContestant(congregationId, req)
        val inheritedOwner = ownerForContestant(contestant)
        val code = freshClaimCode()
        val individualId = UUID.randomUUID().toString()
        IndividualsTable.insert {
            it[id] = individualId
            it[registrationId] = regId
            it[IndividualsTable.contestantId] = contestant
            it[shirtSize] = req.shirtSize.name
            it[claimCode] = code
            // A returning adult re-added by name inherits their existing owner (claim persists).
            it[ownerUserId] = inheritedOwner
            it[tribeLeader] = req.tribeLeaderWilling
        }
        touch(regId)
        RosterEntryDto(
            individualId, req.name.trim(), birthdate = null,
            shirtSize = req.shirtSize, gender = req.gender, claimCode = code, claimed = inheritedOwner != null,
            tribeLeaderWilling = req.tribeLeaderWilling,
        )
    }

    override fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto? =
        transaction(db) {
            val row = IndividualsTable.selectAll().where { IndividualsTable.id eq individualId }.singleOrNull()
                ?: return@transaction null
            val previousContestant = row[IndividualsTable.contestantId]
            val contestant = findOrCreateAdultContestant(congregationIdFor(row[IndividualsTable.registrationId]), req)
            IndividualsTable.update({ IndividualsTable.id eq individualId }) {
                it[shirtSize] = req.shirtSize.name
                it[IndividualsTable.contestantId] = contestant
                it[tribeLeader] = req.tribeLeaderWilling
            }
            if (previousContestant != null && previousContestant != contestant) pruneContestantIfOrphaned(previousContestant)
            individualEntry(individualId)
        }

    /** Reads one individual as a [RosterEntryDto], joining the contestant for its identity. */
    private fun individualEntry(individualId: String): RosterEntryDto? =
        (IndividualsTable innerJoin ContestantsTable).selectAll()
            .where { IndividualsTable.id eq individualId }.singleOrNull()?.toIndividual()

    override fun deleteIndividual(individualId: String): Boolean = transaction(db) {
        val contestantId = IndividualsTable.selectAll().where { IndividualsTable.id eq individualId }
            .singleOrNull()?.get(IndividualsTable.contestantId)
        val deleted = IndividualsTable.deleteWhere { IndividualsTable.id eq individualId } > 0
        if (deleted && contestantId != null) pruneContestantIfOrphaned(contestantId)
        deleted
    }

    override fun addGuest(congregationId: String, seasonYear: String, req: UpsertGuestRequest): GuestDto =
        transaction(db) {
            val regId = regIdFor(congregationId, seasonYear)
            val guestId = UUID.randomUUID().toString()
            RegistrationGuestsTable.insert {
                it[id] = guestId
                it[registrationId] = regId
                it[name] = req.name.trim()
                it[shirtSize] = req.shirtSize?.name
                it[birthdate] = req.birthdate
                it[gender] = req.gender?.name
                it[positions] = encodePositions(req.positions)
                it[tribeLeader] = req.tribeLeaderWilling
                it[contactAddress] = req.contact?.address?.trim() ?: ""
                it[contactCity] = req.contact?.city?.trim() ?: ""
                it[contactState] = req.contact?.state?.trim() ?: ""
                it[contactZip] = req.contact?.zip?.trim() ?: ""
                it[contactPhone] = req.contact?.phone?.trim() ?: ""
                it[contactEmail] = req.contact?.email?.trim() ?: ""
                it[contactPreference] = req.contact?.preference?.name
            }
            touch(regId)
            GuestDto(
                guestId, req.name.trim(), req.shirtSize, req.birthdate, req.gender,
                positions = req.positions, tribeLeaderWilling = req.tribeLeaderWilling, contact = req.contact,
            )
        }

    override fun updateGuest(guestId: String, req: UpsertGuestRequest): GuestDto? = transaction(db) {
        val updated = RegistrationGuestsTable.update({ RegistrationGuestsTable.id eq guestId }) {
            it[name] = req.name.trim()
            it[shirtSize] = req.shirtSize?.name
            it[birthdate] = req.birthdate
            it[gender] = req.gender?.name
            it[positions] = encodePositions(req.positions)
            it[tribeLeader] = req.tribeLeaderWilling
            it[contactAddress] = req.contact?.address?.trim() ?: ""
            it[contactCity] = req.contact?.city?.trim() ?: ""
            it[contactState] = req.contact?.state?.trim() ?: ""
            it[contactZip] = req.contact?.zip?.trim() ?: ""
            it[contactPhone] = req.contact?.phone?.trim() ?: ""
            it[contactEmail] = req.contact?.email?.trim() ?: ""
            it[contactPreference] = req.contact?.preference?.name
        }
        if (updated == 0) null
        else RegistrationGuestsTable.selectAll().where { RegistrationGuestsTable.id eq guestId }.single().toGuest()
    }

    override fun deleteGuest(guestId: String): Boolean = transaction(db) {
        RegistrationGuestsTable.deleteWhere { RegistrationGuestsTable.id eq guestId } > 0
    }

    override fun setSite(congregationId: String, seasonYear: String, siteId: String): RegistrationDto =
        transaction(db) {
            val regId = regIdFor(congregationId, seasonYear)
            RegistrationsTable.update({ RegistrationsTable.id eq regId }) {
                it[RegistrationsTable.siteId] = siteId
                it[updatedAtEpochMs] = System.currentTimeMillis()
            }
            regRow(congregationId, seasonYear)!!.toDto()
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
        // Via the registration link, so it resolves even for an unassigned (teamless) member.
        val regId = TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }
            .singleOrNull()?.get(TeamMembersTable.registrationId) ?: return@transaction null
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)
    }

    override fun congregationIdForIndividual(individualId: String): String? = transaction(db) {
        val regId = IndividualsTable.selectAll().where { IndividualsTable.id eq individualId }
            .singleOrNull()?.get(IndividualsTable.registrationId) ?: return@transaction null
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)
    }

    override fun congregationIdForGuest(guestId: String): String? = transaction(db) {
        val regId = RegistrationGuestsTable.selectAll().where { RegistrationGuestsTable.id eq guestId }
            .singleOrNull()?.get(RegistrationGuestsTable.registrationId) ?: return@transaction null
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)
    }

    override fun contestantIdForMember(memberId: String): String? = transaction(db) {
        TeamMembersTable.selectAll().where { TeamMembersTable.id eq memberId }
            .singleOrNull()?.get(TeamMembersTable.contestantId)
    }

    override fun returningContestants(congregationId: String, seasonYear: String): List<ReturningContestantDto> =
        transaction(db) {
            val seasonRegId = regRow(congregationId, seasonYear)?.get(RegistrationsTable.id)
            // Enrolled this season = has a team OR individual entry in this season's registration.
            val enrolledThisSeason = if (seasonRegId == null) emptySet() else (
                TeamMembersTable.selectAll().where { TeamMembersTable.registrationId eq seasonRegId }
                    .mapNotNull { it[TeamMembersTable.contestantId] } +
                    IndividualsTable.selectAll().where { IndividualsTable.registrationId eq seasonRegId }
                        .mapNotNull { it[IndividualsTable.contestantId] }
                ).toSet()
            ContestantsTable.selectAll()
                .where { ContestantsTable.congregationId eq congregationId }
                .orderBy(ContestantsTable.name)
                .filter { it[ContestantsTable.id] !in enrolledThisSeason }
                .map { row ->
                    val cid = row[ContestantsTable.id]
                    // Most recent enrollment of this person (team for youth, individual for adults),
                    // for display + shirt-size prefill.
                    val recentTeam = (TeamMembersTable innerJoin RegistrationsTable).selectAll()
                        .where { TeamMembersTable.contestantId eq cid }
                        .maxByOrNull { it[RegistrationsTable.seasonYear] }
                        ?.let { it[RegistrationsTable.seasonYear] to it[TeamMembersTable.shirtSize] }
                    val recentIndiv = (IndividualsTable innerJoin RegistrationsTable).selectAll()
                        .where { IndividualsTable.contestantId eq cid }
                        .maxByOrNull { it[RegistrationsTable.seasonYear] }
                        ?.let { it[RegistrationsTable.seasonYear] to it[IndividualsTable.shirtSize] }
                    val recent = listOfNotNull(recentTeam, recentIndiv).maxByOrNull { it.first }
                    ReturningContestantDto(
                        contestantId = cid,
                        name = row[ContestantsTable.name],
                        birthdate = row[ContestantsTable.birthdate],
                        gender = row[ContestantsTable.gender]?.let { Gender.valueOf(it) },
                        lastSeasonYear = recent?.first,
                        lastShirtSize = recent?.second?.let { ShirtSize.valueOf(it) },
                        firstSeasonYear = row[ContestantsTable.firstSeasonYear],
                        graduationYear = row[ContestantsTable.graduationYear],
                    )
                }
        }

    override fun enrollContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
        shirtSize: ShirtSize,
        teamId: String?,
        birthdate: String?,
    ): EnrollResult = transaction(db) {
        // The contestant must exist and belong to this congregation.
        val contestant = ContestantsTable.selectAll().where { ContestantsTable.id eq contestantId }.singleOrNull()
            ?.takeIf { it[ContestantsTable.congregationId] == congregationId }
            ?: return@transaction EnrollResult.ContestantNotFound
        // A workbook-seeded youth finally gets their real birthdate here (route validates the grade).
        var storedBirthdate = contestant[ContestantsTable.birthdate]
        if (storedBirthdate == null && contestant[ContestantsTable.graduationYear] != null) {
            storedBirthdate = birthdate ?: return@transaction EnrollResult.BirthdateRequired
            ContestantsTable.update({ ContestantsTable.id eq contestantId }) {
                it[ContestantsTable.birthdate] = storedBirthdate
            }
        }
        val regId = regIdFor(congregationId, seasonYear)
        val code = freshClaimCode()
        // Adults (birthdate-less) enroll as individuals; youth as team members (optionally on a team).
        if (storedBirthdate == null) {
            val already = IndividualsTable.selectAll()
                .where { (IndividualsTable.contestantId eq contestantId) and (IndividualsTable.registrationId eq regId) }
                .any()
            if (already) return@transaction EnrollResult.AlreadyEnrolled
            IndividualsTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[registrationId] = regId
                it[IndividualsTable.contestantId] = contestantId
                it[IndividualsTable.shirtSize] = shirtSize.name
                it[claimCode] = code
                it[ownerUserId] = ownerForContestant(contestantId)
            }
            touch(regId)
            return@transaction EnrollResult.Enrolled
        }
        val alreadyEnrolled = TeamMembersTable.selectAll()
            .where { (TeamMembersTable.contestantId eq contestantId) and (TeamMembersTable.registrationId eq regId) }
            .any()
        if (alreadyEnrolled) return@transaction EnrollResult.AlreadyEnrolled
        if (teamId != null) {
            val team = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
                ?: return@transaction EnrollResult.TeamNotFound
            if (team[TeamsTable.registrationId] != regId) return@transaction EnrollResult.TeamNotFound
            val size = TeamMembersTable.selectAll().where { TeamMembersTable.teamId eq teamId }.count()
            if (size >= MAX_TEAM_SIZE) return@transaction EnrollResult.RosterFull
        }
        val memberId = UUID.randomUUID().toString()
        TeamMembersTable.insert {
            it[id] = memberId
            it[TeamMembersTable.teamId] = teamId
            it[registrationId] = regId
            it[TeamMembersTable.contestantId] = contestantId
            it[TeamMembersTable.shirtSize] = shirtSize.name
            it[claimCode] = code
            // Enrolling a returning contestant carries their existing owner forward (claim persists).
            it[ownerUserId] = ownerForContestant(contestantId)
        }
        touch(regId)
        EnrollResult.Enrolled
    }

    override fun seedMember(
        congregationId: String,
        seasonYear: String,
        teamId: String?,
        member: SeedMemberDto,
    ): RosterEntryDto? = transaction(db) {
        val regId = regIdFor(congregationId, seasonYear)
        if (teamId != null) {
            // Combo rule (item 5): the team may belong to another congregation's same-season
            // registration — the member stays registered (and billed) by their own congregation.
            val teamRegId = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
                ?.get(TeamsTable.registrationId) ?: return@transaction null
            val teamSeason = RegistrationsTable.selectAll().where { RegistrationsTable.id eq teamRegId }
                .singleOrNull()?.get(RegistrationsTable.seasonYear)
            if (teamSeason != seasonYear) return@transaction null
        }
        val trimmed = member.name.trim()
        val existing = ContestantsTable.selectAll()
            .where {
                (ContestantsTable.congregationId eq congregationId) and
                    (ContestantsTable.name.lowerCase() eq trimmed.lowercase())
            }
            .firstOrNull()
        val contestantId = existing?.get(ContestantsTable.id) ?: UUID.randomUUID().toString()
        // The seed fills identity gaps but never overwrites curated data: birthdate wins over the
        // seeded grade, and an inexperienced seed can only pull the first season earlier.
        val firstSeason =
            if (member.inexperienced) {
                listOfNotNull(existing?.get(ContestantsTable.firstSeasonYear), seasonYear).min()
            } else existing?.get(ContestantsTable.firstSeasonYear)
        if (existing == null) {
            ContestantsTable.insert {
                it[id] = contestantId
                it[ContestantsTable.congregationId] = congregationId
                it[name] = trimmed
                it[gender] = member.gender?.name
                it[firstSeasonYear] = firstSeason
                it[graduationYear] = graduationYearFor(seasonYear, member.grade)
            }
        } else {
            ContestantsTable.update({ ContestantsTable.id eq contestantId }) {
                if (existing[ContestantsTable.gender] == null) it[gender] = member.gender?.name
                if (existing[ContestantsTable.birthdate] == null) {
                    it[graduationYear] = graduationYearFor(seasonYear, member.grade)
                }
                it[firstSeasonYear] = firstSeason
            }
        }
        val enrollment = TeamMembersTable.selectAll()
            .where { (TeamMembersTable.contestantId eq contestantId) and (TeamMembersTable.registrationId eq regId) }
            .firstOrNull()
        val entryId: String
        if (enrollment == null) {
            entryId = UUID.randomUUID().toString()
            val code = freshClaimCode()
            TeamMembersTable.insert {
                it[id] = entryId
                it[TeamMembersTable.teamId] = null
                it[registrationId] = regId
                it[TeamMembersTable.contestantId] = contestantId
                it[shirtSize] = member.shirtSize.name
                it[claimCode] = code
                it[ownerUserId] = ownerForContestant(contestantId)
            }
        } else {
            entryId = enrollment[TeamMembersTable.id]
            TeamMembersTable.update({ TeamMembersTable.id eq entryId }) {
                it[shirtSize] = member.shirtSize.name
            }
        }
        // (Re)place on the team when there's room; a full team leaves the entry unassigned.
        if (teamId != null && enrollment?.get(TeamMembersTable.teamId) != teamId) {
            val size = TeamMembersTable.selectAll().where { TeamMembersTable.teamId eq teamId }.count()
            if (size < MAX_TEAM_SIZE) {
                TeamMembersTable.update({ TeamMembersTable.id eq entryId }) { it[TeamMembersTable.teamId] = teamId }
            }
        }
        touch(regId)
        memberEntry(entryId)
    }

    override fun listForSeason(seasonYear: String): List<RegistrationDto> = transaction(db) {
        RegistrationsTable.selectAll()
            .where { RegistrationsTable.seasonYear eq seasonYear }
            .map { it.toDto() }
    }

    override fun seasonYears(): List<String> = transaction(db) {
        RegistrationsTable.select(RegistrationsTable.seasonYear).withDistinct()
            .map { it[RegistrationsTable.seasonYear] }
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
            // Ownership is durable per person: claiming any of a youth contestant's entries owns them
            // all (across seasons), so a parent claims once and future enrollments are theirs too.
            val contestantId = row[TeamMembersTable.contestantId]
            val siblings = if (contestantId != null)
                TeamMembersTable.selectAll().where { TeamMembersTable.contestantId eq contestantId }.toList()
            else listOf(row)
            val existingOwner = siblings.firstNotNullOfOrNull { it[TeamMembersTable.ownerUserId] }
            if (existingOwner != null && existingOwner != userId) return@transaction ClaimResult.AlreadyClaimed
            val target = if (contestantId != null) TeamMembersTable.contestantId eq contestantId
                else TeamMembersTable.id eq row[TeamMembersTable.id]
            TeamMembersTable.update({ target }) { it[ownerUserId] = userId }
            // Read back through the contestant join (toEntry sources identity there) — now owned.
            return@transaction memberEntry(row[TeamMembersTable.id])?.let { ClaimResult.Claimed(it) }
                ?: ClaimResult.NotFound
        }
        IndividualsTable.selectAll().where { IndividualsTable.claimCode eq code }.singleOrNull()?.let { row ->
            // Durable ownership for adults too: claiming any of a contestant's individual entries owns
            // them all (across seasons), so an adult claims once and future entries are theirs.
            val contestantId = row[IndividualsTable.contestantId]
            val siblings = if (contestantId != null)
                IndividualsTable.selectAll().where { IndividualsTable.contestantId eq contestantId }.toList()
            else listOf(row)
            val existingOwner = siblings.firstNotNullOfOrNull { it[IndividualsTable.ownerUserId] }
            if (existingOwner != null && existingOwner != userId) return@transaction ClaimResult.AlreadyClaimed
            val target = if (contestantId != null) IndividualsTable.contestantId eq contestantId
                else IndividualsTable.id eq row[IndividualsTable.id]
            IndividualsTable.update({ target }) { it[ownerUserId] = userId }
            // Read back through the contestant join (toIndividual sources identity there) — now owned.
            return@transaction individualEntry(row[IndividualsTable.id])?.let { ClaimResult.Claimed(it) }
                ?: ClaimResult.NotFound
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

    /** The name of a registration's congregation, for labeling cross-congregation (combo) members. */
    private fun congregationNameForReg(regId: String): String =
        CongregationsTable.selectAll().where { CongregationsTable.id eq congregationIdFor(regId) }
            .singleOrNull()?.get(CongregationsTable.name) ?: "?"

    private fun teamDto(teamId: String): TeamDto? =
        TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()?.let { row ->
            val homeRegId = row[TeamsTable.registrationId]
            TeamDto(
                id = row[TeamsTable.id],
                name = row[TeamsTable.name],
                members = (TeamMembersTable innerJoin ContestantsTable).selectAll()
                    .where { TeamMembersTable.teamId eq teamId }
                    .map { memberRow ->
                        val entry = memberRow.toEntry()
                        val memberRegId = memberRow[TeamMembersTable.registrationId]
                        // A visiting (combo-team) member carries their own congregation.
                        if (memberRegId == homeRegId) entry
                        else entry.copy(
                            congregationId = congregationIdFor(memberRegId),
                            congregationName = congregationNameForReg(memberRegId),
                        )
                    },
            )
        }

    /** Builds a roster entry from a row that joins [TeamMembersTable] to its [ContestantsTable] (the
     *  source of identity); the enrollment supplies only shirt size, claim code, and ownership. */
    private fun ResultRow.toEntry() = RosterEntryDto(
        id = this[TeamMembersTable.id],
        name = this[ContestantsTable.name],
        birthdate = this[ContestantsTable.birthdate],
        shirtSize = ShirtSize.valueOf(this[TeamMembersTable.shirtSize]),
        gender = this[ContestantsTable.gender]?.let { Gender.valueOf(it) },
        firstSeasonYear = this[ContestantsTable.firstSeasonYear],
        claimCode = this[TeamMembersTable.claimCode],
        claimed = this[TeamMembersTable.ownerUserId] != null,
    )

    private fun ResultRow.toGuest() = GuestDto(
        id = this[RegistrationGuestsTable.id],
        name = this[RegistrationGuestsTable.name],
        shirtSize = this[RegistrationGuestsTable.shirtSize]?.let { ShirtSize.valueOf(it) },
        birthdate = this[RegistrationGuestsTable.birthdate],
        gender = this[RegistrationGuestsTable.gender]?.let { Gender.valueOf(it) },
        positions = decodePositions(this[RegistrationGuestsTable.positions]),
        tribeLeaderWilling = this[RegistrationGuestsTable.tribeLeader],
        contact = ContactInfoDto(
            address = this[RegistrationGuestsTable.contactAddress],
            city = this[RegistrationGuestsTable.contactCity],
            state = this[RegistrationGuestsTable.contactState],
            zip = this[RegistrationGuestsTable.contactZip],
            phone = this[RegistrationGuestsTable.contactPhone],
            email = this[RegistrationGuestsTable.contactEmail],
            preference = this[RegistrationGuestsTable.contactPreference]?.let { ContactPreference.valueOf(it) },
        ).takeUnless { it.isEmpty() },
    )

    /** Builds an individual entry from a row that joins [IndividualsTable] to its [ContestantsTable]. */
    private fun ResultRow.toIndividual() = RosterEntryDto(
        id = this[IndividualsTable.id],
        name = this[ContestantsTable.name],
        birthdate = null,
        shirtSize = ShirtSize.valueOf(this[IndividualsTable.shirtSize]),
        gender = this[ContestantsTable.gender]?.let { Gender.valueOf(it) },
        claimCode = this[IndividualsTable.claimCode],
        claimed = this[IndividualsTable.ownerUserId] != null,
        tribeLeaderWilling = this[IndividualsTable.tribeLeader],
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
                phone = cong?.get(CongregationsTable.phone) ?: "",
                code = cong?.get(CongregationsTable.code) ?: "",
            ),
            seasonYear = this[RegistrationsTable.seasonYear],
            status = RegistrationStatus.valueOf(this[RegistrationsTable.status]),
            siteId = this[RegistrationsTable.siteId],
            teams = TeamsTable.selectAll()
                .where { TeamsTable.registrationId eq regId }
                .orderBy(TeamsTable.sortOrder)
                .map { row -> teamDto(row[TeamsTable.id])!! },
            individuals = (IndividualsTable innerJoin ContestantsTable).selectAll()
                .where { IndividualsTable.registrationId eq regId }
                .orderBy(ContestantsTable.name)
                .map { it.toIndividual() },
            unassigned = (TeamMembersTable innerJoin ContestantsTable).selectAll()
                .where { (TeamMembersTable.registrationId eq regId) and TeamMembersTable.teamId.isNull() }
                .orderBy(ContestantsTable.name)
                .map { it.toEntry() },
            awayMembers = (TeamMembersTable innerJoin ContestantsTable innerJoin TeamsTable).selectAll()
                .where { (TeamMembersTable.registrationId eq regId) and (TeamsTable.registrationId neq regId) }
                .orderBy(ContestantsTable.name)
                .map { row ->
                    AwayMemberDto(
                        entry = row.toEntry(),
                        teamId = row[TeamsTable.id],
                        teamName = row[TeamsTable.name],
                        congregationName = congregationNameForReg(row[TeamsTable.registrationId]),
                    )
                },
            guests = RegistrationGuestsTable.selectAll()
                .where { RegistrationGuestsTable.registrationId eq regId }
                .orderBy(RegistrationGuestsTable.name)
                .map { it.toGuest() },
            submittedAt = this[RegistrationsTable.submittedAtEpochMs]?.let { Instant.ofEpochMilli(it).toString() },
            paidAt = this[RegistrationsTable.paidAtEpochMs]?.let { Instant.ofEpochMilli(it).toString() },
        )
    }
}
