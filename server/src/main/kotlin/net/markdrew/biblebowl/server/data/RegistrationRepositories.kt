package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.AwayMemberDto
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
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
    /** No such person known to this congregation. */
    data object ContestantNotFound : EnrollResult
    /** The caller's `eligible` predicate rejected the contestant (e.g. aged out of youth divisions). */
    data object NotEligible : EnrollResult
    /** The person is already enrolled as a contestant this season. */
    data object AlreadyEnrolled : EnrollResult
    /** The target team doesn't exist or belongs to another registration. */
    data object TeamNotFound : EnrollResult
    data object RosterFull : EnrollResult
    /** A workbook-seeded youth (grade, no birthdate) needs a birthdate at first enrollment. */
    data object BirthdateRequired : EnrollResult
}

/** Outcome of claiming a person by their coach-shared code. */
sealed interface ClaimResult {
    data class Claimed(val entry: RosterEntryDto) : ClaimResult
    data object NotFound : ClaimResult
    /** The code matches a person already claimed by a different account. */
    data object AlreadyClaimed : ClaimResult
}

/**
 * A congregation's registration for one season (unique per congregation+seasonYear), with its
 * teams and rosters. [RegistrationDto.totalCents] is left null here — routes compute it from the
 * current season's fees.
 *
 * Since V2 the storage is person-centric (people + participants); the wire shapes are unchanged:
 * roster-entry/guest ids are participant ids, and "contestant ids" are person ids.
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
     * any team exists. An id that matches none of the season's sites stores null ("not chosen").
     */
    fun setSite(congregationId: String, seasonYear: String, siteId: String): RegistrationDto
    /** Marks the registration SUBMITTED (idempotent; re-submit refreshes the timestamp). */
    fun submit(congregationId: String, seasonYear: String): RegistrationDto?
    /** Scoping lookups: which congregation a team/member/individual belongs to (for permission checks). */
    fun congregationIdForTeam(teamId: String): String?
    fun congregationIdForMember(memberId: String): String?
    fun congregationIdForIndividual(individualId: String): String?
    fun congregationIdForGuest(guestId: String): String?
    /** The person a roster entry belongs to; null when the entry doesn't exist. */
    fun contestantIdForMember(memberId: String): String?
    /**
     * People with contestant history at [congregationId] and no enrollment in [seasonYear] —
     * returning *candidates*, offered for one-click enrollment. Youth-eligibility filtering
     * (division) is the caller's, since it needs the season; here they're returned name-sorted
     * with last-seen details.
     */
    fun returningContestants(congregationId: String, seasonYear: String): List<ReturningContestantDto>
    /**
     * The single returning candidate [contestantId] (a person id) — null when the person has no
     * history with [congregationId] or is already enrolled in [seasonYear]. Last-seen details
     * (season, shirt) are omitted: this is the eligibility-check form, not the display form.
     */
    fun returningContestant(congregationId: String, seasonYear: String, contestantId: String): ReturningContestantDto?
    /**
     * Creates [seasonYear]'s enrollment for an existing person (on [teamId], else unassigned).
     * [birthdate] is for workbook-seeded youth (grade but no birthdate): required for them —
     * [EnrollResult.BirthdateRequired] otherwise — and written onto the person, who is a normal
     * birthdate-carrying youth from then on. Ignored for everyone else. [eligible] is evaluated
     * on the person's *stored* identity (pre-[birthdate] adoption) inside the same transaction —
     * routes pass the season's age rule so no extra eligibility round trip is needed. A person
     * already attending this season as a guest is upgraded to a contestant in place.
     */
    fun enrollContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
        shirtSize: ShirtSize,
        teamId: String?,
        birthdate: String? = null,
        eligible: (ReturningContestantDto) -> Boolean = { true },
    ): EnrollResult
    /**
     * Workbook seed (item 17, F13): upserts the person for `(congregationId, name)` — grade-seeded,
     * no birthdate ([SeedMemberDto.grade] becomes a graduation year) — plus their [seasonYear]
     * enrollment on [teamId] (null = unassigned). Idempotent: an existing enrollment is updated
     * (shirt size, team) rather than duplicated, and a person who already carries a birthdate
     * keeps it (the seed only fills identity gaps). Null when the team doesn't exist.
     */
    fun seedMember(congregationId: String, seasonYear: String, teamId: String?, member: SeedMemberDto): RosterEntryDto?
    /** Every registration for [seasonYear], with full teams/rosters (registration desk). */
    fun listForSeason(seasonYear: String): List<RegistrationDto>
    /** Every season year with at least one registration (any status) — the desk's year picker. */
    fun seasonYears(): List<String>
    /** Sets (non-null) or clears (null) payment received. Null return = no such registration. */
    fun setPaid(registrationId: String, paidAtEpochMs: Long?): RegistrationDto?
    /** Links the person with claim code [code] to [userId] (durable across seasons). Idempotent for the same account. */
    fun claimEntry(code: String, userId: String): ClaimResult
    /** Ids of every roster entry (team member or individual) of people claimed by [userId]. */
    fun entryIdsOwnedBy(userId: String): Set<String>
    /**
     * The [seasonYear] participant (contestant or guest) whose person is named [name],
     * case-insensitively; null when no registered attendee matches. Used to validate that tribe
     * leaders are registered attendees.
     */
    fun participantIdByName(seasonYear: String, name: String): String?
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

/**
 * In-memory mirror of the person-centric model: durable people (with the person-level claim code
 * and manager introduced by V2) plus per-season entries. Wire behavior matches the Postgres
 * implementation; only the storage is simplified.
 */
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

    /** A durable person (see [PeopleTable]): identity plus the V2 person-level claim code/manager. */
    private data class Contestant(
        val id: String,
        val congregationId: String,
        var name: String,
        // Mutable for exactly one transition: a workbook-seeded youth gets their real birthdate
        // at first enrollment (see RegistrationRepository.enrollContestant).
        var birthdate: String?,
        var gender: Gender?,
        var firstSeasonYear: String?,
        /** Seeded grade as a graduation year (see [PeopleTable.graduationYear]); birthdate wins. */
        var graduationYear: Int? = null,
        /** The person's claim code — shared by all their entries since V2. */
        val claimCode: String = ClaimCodes.generate(),
        /** The account managing this person (claimed); durable across seasons. */
        var ownerUserId: String? = null,
    )

    private val regs = mutableMapOf<String, Reg>() // key = "$congregationId|$seasonYear"
    private val teams = mutableMapOf<String, Team>()
    private val members = mutableMapOf<String, RosterEntryDto>()
    private val memberTeam = mutableMapOf<String, String>() // member id -> team id (absent = unassigned)
    private val memberReg = mutableMapOf<String, String>() // member id -> registration id (always set)
    private val contestants = mutableMapOf<String, Contestant>() // durable person id -> person
    private val memberContestant = mutableMapOf<String, String>() // team member id -> person id
    private val individualContestant = mutableMapOf<String, String>() // individual id -> person id
    private val individuals = mutableMapOf<String, RosterEntryDto>()
    private val individualReg = mutableMapOf<String, String>()
    private val guests = mutableMapOf<String, GuestDto>()
    private val guestReg = mutableMapOf<String, String>()
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
        val (contestantId, firstYear) = findOrCreateContestant(reg.congregationId, req, reg.seasonYear)
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            birthdate = req.birthdate,
            shirtSize = req.shirtSize,
            gender = req.gender,
            firstSeasonYear = firstYear,
            claimCode = contestants.getValue(contestantId).claimCode,
        )
        members[entry.id] = entry
        memberTeam[entry.id] = teamId
        memberReg[entry.id] = reg.id
        memberContestant[entry.id] = contestantId
        team.memberIds += entry.id
        AddMemberResult.Added(memberDto(entry.id) ?: entry)
    }

    /** True when [contestantId] has an enrollment in a season earlier than [seasonYear]. */
    private fun contestantHasPriorSeason(contestantId: String, seasonYear: String): Boolean =
        memberContestant.filterValues { it == contestantId }.keys.any { mid ->
            memberReg[mid]?.let { regId -> regs.values.firstOrNull { it.id == regId }?.seasonYear }
                ?.let { it < seasonYear } == true
        }

    /** True when the person already has any entry (member/individual) in [seasonYear]. */
    private fun enrolledInSeason(contestantId: String, seasonYear: String, exceptEntryId: String? = null): Boolean {
        fun regSeason(regId: String?): String? = regId?.let { r -> regs.values.firstOrNull { it.id == r }?.seasonYear }
        return memberContestant.any { (mid, cid) ->
            cid == contestantId && mid != exceptEntryId && regSeason(memberReg[mid]) == seasonYear
        } || individualContestant.any { (iid, cid) ->
            cid == contestantId && iid != exceptEntryId && regSeason(individualReg[iid]) == seasonYear
        }
    }

    /**
     * Finds the durable person for `(congregation, name, birthdate)` — reused across seasons, so
     * re-adding last year's contestant recognizes the same person — or creates one, refreshing name
     * and gender. A person already enrolled this season is never matched (one enrollment per person
     * per season): the coach gets a fresh (duplicate) person, phase-6 merge-tool territory. Returns
     * the person id and the resolved first season year: locked to the earliest once the person has
     * competed before, else this season when the coach marked them inexperienced.
     */
    private fun findOrCreateContestant(
        congregationId: String,
        req: UpsertRosterEntryRequest,
        seasonYear: String,
        exceptEntryId: String? = null,
    ): Pair<String, String?> {
        val trimmed = req.name.trim()
        val existing = contestants.values.firstOrNull {
            it.congregationId == congregationId &&
                it.name.equals(trimmed, ignoreCase = true) &&
                it.birthdate == req.birthdate &&
                !enrolledInSeason(it.id, seasonYear, exceptEntryId)
        } ?: contestants.values.firstOrNull {
            // A workbook-seeded youth (no birthdate yet) re-added by name WITH a birthdate is the
            // same person — adopt the birthdate instead of creating a duplicate.
            it.congregationId == congregationId && it.name.equals(trimmed, ignoreCase = true) &&
                it.birthdate == null && it.graduationYear != null &&
                !enrolledInSeason(it.id, seasonYear, exceptEntryId)
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
        val (contestantId, firstYear) =
            findOrCreateContestant(reg.congregationId, req, reg.seasonYear, exceptEntryId = memberId)
        members[memberId] = entry.copy(
            name = req.name.trim(),
            birthdate = req.birthdate,
            shirtSize = req.shirtSize,
            gender = req.gender,
            firstSeasonYear = firstYear,
        )
        // Re-point at the (possibly different) durable person — the person may have been renamed
        // or had their birthdate corrected — and prune the old one if nothing else references it.
        val previous = memberContestant[memberId]
        memberContestant[memberId] = contestantId
        previous?.takeIf { it != contestantId }?.let { pruneContestantIfOrphaned(it) }
        memberDto(memberId)
    }

    /** Drops a durable person once no entry (team or individual, any season) references it. */
    private fun pruneContestantIfOrphaned(contestantId: String) {
        val used = memberContestant.values.any { it == contestantId } ||
            individualContestant.values.any { it == contestantId }
        if (!used) contestants.remove(contestantId)
    }

    override fun deleteMember(memberId: String): Boolean = synchronized(lock) {
        members.remove(memberId) ?: return false
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
        val contestantId = findOrCreateAdultContestant(congregationId, req, seasonYear)
        val entry = RosterEntryDto(
            id = UUID.randomUUID().toString(),
            name = req.name.trim(),
            birthdate = null,
            shirtSize = req.shirtSize,
            gender = req.gender,
            claimCode = contestants.getValue(contestantId).claimCode,
            tribeLeaderWilling = req.tribeLeaderWilling,
        )
        individuals[entry.id] = entry
        individualReg[entry.id] = reg.id
        individualContestant[entry.id] = contestantId
        reg.individualIds += entry.id
        individualDto(entry.id)!!
    }

    /** Finds or creates the birthdate-less durable person for an adult `(congregation, name)`. */
    private fun findOrCreateAdultContestant(
        congregationId: String,
        req: UpsertIndividualRequest,
        seasonYear: String,
        exceptEntryId: String? = null,
    ): String {
        val trimmed = req.name.trim()
        val existing = contestants.values.firstOrNull {
            // graduationYear == null: a workbook-seeded youth is also birthdate-less but is NOT
            // this adult (the seed marks youth with a graduation year).
            it.congregationId == congregationId && it.name.equals(trimmed, ignoreCase = true) &&
                it.birthdate == null && it.graduationYear == null &&
                !enrolledInSeason(it.id, seasonYear, exceptEntryId)
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
                val seasonYear = individualReg[individualId]
                    ?.let { regId -> regs.values.firstOrNull { it.id == regId }?.seasonYear }
                val previous = individualContestant[individualId]
                val contestantId = findOrCreateAdultContestant(
                    congregationId, req, seasonYear ?: "", exceptEntryId = individualId,
                )
                individualContestant[individualId] = contestantId
                previous?.takeIf { it != contestantId }?.let { pruneContestantIfOrphaned(it) }
            }
            individualDto(individualId)
        }

    override fun deleteIndividual(individualId: String): Boolean = synchronized(lock) {
        individuals.remove(individualId) ?: return false
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

    override fun returningContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
    ): ReturningContestantDto? = synchronized(lock) {
        val contestant = contestants[contestantId]?.takeIf { it.congregationId == congregationId }
            ?: return null
        val seasonRegId = regs["$congregationId|$seasonYear"]?.id
        val enrolled = seasonRegId != null && (
            memberContestant.any { (mid, cid) -> cid == contestantId && memberReg[mid] == seasonRegId } ||
                individualContestant.any { (iid, cid) -> cid == contestantId && individualReg[iid] == seasonRegId }
            )
        if (enrolled) return null
        ReturningContestantDto(
            contestantId = contestant.id,
            name = contestant.name,
            birthdate = contestant.birthdate,
            gender = contestant.gender,
            firstSeasonYear = contestant.firstSeasonYear,
            graduationYear = contestant.graduationYear,
        )
    }

    override fun enrollContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
        shirtSize: ShirtSize,
        teamId: String?,
        birthdate: String?,
        eligible: (ReturningContestantDto) -> Boolean,
    ): EnrollResult = synchronized(lock) {
        val contestant = contestants[contestantId]?.takeIf { it.congregationId == congregationId }
            ?: return EnrollResult.ContestantNotFound
        // Eligibility goes by the stored identity — a seeded youth is judged by graduation year
        // even though the real birthdate is adopted just below.
        val candidate = ReturningContestantDto(
            contestantId = contestant.id,
            name = contestant.name,
            birthdate = contestant.birthdate,
            graduationYear = contestant.graduationYear,
        )
        if (!eligible(candidate)) return EnrollResult.NotEligible
        // A workbook-seeded youth finally gets their real birthdate here (route validates the grade).
        if (contestant.birthdate == null && contestant.graduationYear != null) {
            contestant.birthdate = birthdate ?: return EnrollResult.BirthdateRequired
        }
        val reg = regFor(congregationId, seasonYear)
        // Adults (birthdate-less) enroll as individuals; youth as team members (optionally on a team).
        if (contestant.birthdate == null) {
            val already = individualContestant.any { (iid, cid) -> cid == contestantId && individualReg[iid] == reg.id }
            if (already) return EnrollResult.AlreadyEnrolled
            val entry = RosterEntryDto(
                id = UUID.randomUUID().toString(), name = contestant.name, birthdate = null,
                shirtSize = shirtSize, gender = contestant.gender, claimCode = contestant.claimCode,
            )
            individuals[entry.id] = entry
            individualReg[entry.id] = reg.id
            individualContestant[entry.id] = contestantId
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
            claimCode = contestant.claimCode,
        )
        members[entry.id] = entry
        memberReg[entry.id] = reg.id
        memberContestant[entry.id] = contestantId
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
            members[entryId] = RosterEntryDto(
                id = entryId, name = trimmed, birthdate = contestant.birthdate,
                shirtSize = member.shirtSize, gender = contestant.gender,
                firstSeasonYear = contestant.firstSeasonYear, claimCode = contestant.claimCode,
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
        // Ownership is durable per person since V2: the claim code IS the person's, so claiming
        // it owns every entry of theirs (across seasons) at once.
        val contestant = contestants.values.firstOrNull { it.claimCode == code } ?: return ClaimResult.NotFound
        if (contestant.ownerUserId != null && contestant.ownerUserId != userId) return ClaimResult.AlreadyClaimed
        contestant.ownerUserId = userId
        val latestEntry = (memberContestant.filterValues { it == contestant.id }.keys.mapNotNull { memberDto(it) } +
            individualContestant.filterValues { it == contestant.id }.keys.mapNotNull { individualDto(it) })
            .lastOrNull()
            ?: return ClaimResult.NotFound
        ClaimResult.Claimed(latestEntry)
    }

    override fun entryIdsOwnedBy(userId: String): Set<String> = synchronized(lock) {
        val owned = contestants.values.filter { it.ownerUserId == userId }.map { it.id }.toSet()
        (memberContestant.filterValues { it in owned }.keys +
            individualContestant.filterValues { it in owned }.keys).toSet()
    }

    override fun participantIdByName(seasonYear: String, name: String): String? = synchronized(lock) {
        val trimmed = name.trim()
        val seasonRegIds = regs.values.filter { it.seasonYear == seasonYear }.map { it.id }.toSet()
        members.keys.firstOrNull { mid ->
            memberReg[mid] in seasonRegIds && memberDto(mid)?.name.equals(trimmed, ignoreCase = true)
        } ?: individuals.keys.firstOrNull { iid ->
            individualReg[iid] in seasonRegIds && individualDto(iid)?.name.equals(trimmed, ignoreCase = true)
        } ?: guests.keys.firstOrNull { gid ->
            guestReg[gid] in seasonRegIds && guests[gid]?.name.equals(trimmed, ignoreCase = true)
        }
    }

    /** A team roster entry with its identity sourced from the durable person and ownership resolved. */
    private fun memberDto(id: String): RosterEntryDto? {
        val entry = members[id] ?: return null
        val contestant = memberContestant[id]?.let { contestants[it] }
        return entry.copy(
            name = contestant?.name ?: entry.name,
            birthdate = contestant?.birthdate ?: entry.birthdate,
            gender = contestant?.gender ?: entry.gender,
            firstSeasonYear = if (contestant != null) contestant.firstSeasonYear else entry.firstSeasonYear,
            claimCode = contestant?.claimCode ?: entry.claimCode,
            claimed = contestant?.ownerUserId != null,
        )
    }

    /** An individual (adult) entry with its identity sourced from the durable person and claimed resolved. */
    private fun individualDto(id: String): RosterEntryDto? {
        val entry = individuals[id] ?: return null
        val contestant = individualContestant[id]?.let { contestants[it] }
        return entry.copy(
            name = contestant?.name ?: entry.name,
            gender = contestant?.gender ?: entry.gender,
            claimCode = contestant?.claimCode ?: entry.claimCode,
            claimed = contestant?.ownerUserId != null,
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
