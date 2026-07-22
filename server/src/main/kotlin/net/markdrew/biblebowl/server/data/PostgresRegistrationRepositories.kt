package net.markdrew.biblebowl.server.data

import kotlinx.datetime.LocalDate
import net.markdrew.biblebowl.api.AwayMemberDto
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.GuestDto
import net.markdrew.biblebowl.api.ParticipationDto
import net.markdrew.biblebowl.api.PersonDto
import net.markdrew.biblebowl.api.PersonWithParticipationsDto
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.ReturningContestantDto
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.SeedMemberDto
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TeamDto
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.congregationCodeCandidates
import net.markdrew.biblebowl.api.graduationYearFor
import org.jetbrains.exposed.v1.core.JoinType
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
import java.util.UUID

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

/**
 * Person-centric registration storage (V2). A "roster entry"/"guest" on the wire is a
 * [ParticipantsTable] row; a "contestant id" is a [PeopleTable] id. Identity (name, birthdate,
 * gender, experience anchor) lives on the person and is joined back to reproduce the unchanged
 * DTO shapes; the participation carries only per-season facts (team, shirt, positions, tester id).
 *
 * `is_contestant = true` participants split into "team members" (youth — birthdate present, so
 * eligible for a team) and "individuals" (adults — birthdate null), matching the pre-V2 two-table
 * split the wire DTOs still assume. Guests are `is_contestant = false`.
 */
class PostgresRegistrationRepository(private val db: Database) : RegistrationRepository, PeopleRepository {

    // --- person-centric API (PeopleRepository) --------------------------------

    override fun claimPerson(code: String, userId: String): PersonClaimResult = transaction(db) {
        val row = PeopleTable.selectAll().where { PeopleTable.claimCode eq code }.singleOrNull()
            ?: return@transaction PersonClaimResult.NotFound
        val manager = row[PeopleTable.managedByUserId]
        if (manager != null && manager != userId) return@transaction PersonClaimResult.AlreadyClaimed
        PeopleTable.update({ PeopleTable.id eq row[PeopleTable.id] }) { it[managedByUserId] = userId }
        PersonClaimResult.Claimed(row.toPersonDto())
    }

    override fun personWithParticipations(personId: String): PersonWithParticipationsDto? = transaction(db) {
        val row = PeopleTable.selectAll().where { PeopleTable.id eq personId }.singleOrNull()
            ?: return@transaction null
        PersonWithParticipationsDto(row.toPersonDto(), participationsOf(personId))
    }

    override fun peopleManagedBy(userId: String): List<PersonWithParticipationsDto> = transaction(db) {
        PeopleTable.selectAll().where { PeopleTable.managedByUserId eq userId }
            .orderBy(PeopleTable.name)
            .map { it.toPersonDto() }
            .map { PersonWithParticipationsDto(it, participationsOf(it.id)) }
    }

    override fun searchPeople(query: String, limit: Int): List<PersonWithParticipationsDto> = transaction(db) {
        // A blank query yields "%%", which LIKE matches against every name — the intended "list all".
        val q = "%${query.trim().lowercase()}%"
        PeopleTable.selectAll()
            .where { PeopleTable.name.lowerCase() like q }
            .orderBy(PeopleTable.name)
            .limit(limit)
            .map { it.toPersonDto() }
            .map { PersonWithParticipationsDto(it, participationsOf(it.id)) }
    }

    override fun mergePeople(keepId: String, mergeId: String): MergeResult = transaction(db) {
        if (keepId == mergeId) return@transaction MergeResult.NotFound
        val keep = PeopleTable.selectAll().where { PeopleTable.id eq keepId }.singleOrNull()
            ?: return@transaction MergeResult.NotFound
        val merge = PeopleTable.selectAll().where { PeopleTable.id eq mergeId }.singleOrNull()
            ?: return@transaction MergeResult.NotFound
        fun seasonsOf(personId: String) = ParticipantsTable.selectAll()
            .where { ParticipantsTable.personId eq personId }
            .map { it[ParticipantsTable.seasonYear] }.toSet()
        val overlap = seasonsOf(keepId) intersect seasonsOf(mergeId)
        if (overlap.isNotEmpty()) {
            return@transaction MergeResult.SeasonConflict(overlap.sorted().map { it.toString() })
        }
        // Move everything that points at the loser onto the survivor. Scores, tester ids, and
        // tribe-leader rows follow automatically — they FK the participant, not the person.
        ParticipantsTable.update({ ParticipantsTable.personId eq mergeId }) { it[personId] = keepId }
        CheckoutDutiesTable.update({ CheckoutDutiesTable.personId eq mergeId }) { it[personId] = keepId }
        UsersTable.update({ UsersTable.personId eq mergeId }) { it[personId] = keepId }
        // Fill the survivor's empty identity/contact fields from the loser (survivor's values win).
        PeopleTable.update({ PeopleTable.id eq keepId }) {
            if (keep[PeopleTable.birthdate] == null) it[birthdate] = merge[PeopleTable.birthdate]
            if (keep[PeopleTable.gender] == null) it[gender] = merge[PeopleTable.gender]
            if (keep[PeopleTable.graduationYear] == null) it[graduationYear] = merge[PeopleTable.graduationYear]
            if (keep[PeopleTable.email] == null) it[email] = merge[PeopleTable.email]
            if (keep[PeopleTable.managedByUserId] == null) it[managedByUserId] = merge[PeopleTable.managedByUserId]
            it[isAdult] = keep[PeopleTable.isAdult] || merge[PeopleTable.isAdult]
            val firstYears = listOfNotNull(keep[PeopleTable.firstSeasonYear], merge[PeopleTable.firstSeasonYear])
            if (firstYears.isNotEmpty()) it[firstSeasonYear] = firstYears.min()
            if (keep[PeopleTable.contactAddress].isBlank()) it[contactAddress] = merge[PeopleTable.contactAddress]
            if (keep[PeopleTable.contactCity].isBlank()) it[contactCity] = merge[PeopleTable.contactCity]
            if (keep[PeopleTable.contactState].isBlank()) it[contactState] = merge[PeopleTable.contactState]
            if (keep[PeopleTable.contactZip].isBlank()) it[contactZip] = merge[PeopleTable.contactZip]
            if (keep[PeopleTable.contactPhone].isBlank()) it[contactPhone] = merge[PeopleTable.contactPhone]
            if (keep[PeopleTable.contactPreference] == null) it[contactPreference] = merge[PeopleTable.contactPreference]
        }
        PeopleTable.deleteWhere { PeopleTable.id eq mergeId }
        val survivor = PeopleTable.selectAll().where { PeopleTable.id eq keepId }.single()
        MergeResult.Merged(PersonWithParticipationsDto(survivor.toPersonDto(), participationsOf(keepId)))
    }

    private fun ResultRow.toPersonDto() = PersonDto(
        id = this[PeopleTable.id],
        name = this[PeopleTable.name],
        birthdate = this[PeopleTable.birthdate]?.toString(),
        isAdult = this[PeopleTable.isAdult],
        gender = this[PeopleTable.gender]?.let { Gender.valueOf(it) },
        graduationYear = this[PeopleTable.graduationYear],
        firstSeasonYear = this[PeopleTable.firstSeasonYear]?.toString(),
        email = this[PeopleTable.email],
        contact = ContactInfoDto(
            address = this[PeopleTable.contactAddress], city = this[PeopleTable.contactCity],
            state = this[PeopleTable.contactState], zip = this[PeopleTable.contactZip],
            phone = this[PeopleTable.contactPhone], email = this[PeopleTable.email].orEmpty(),
            preference = this[PeopleTable.contactPreference]?.let { ContactPreference.valueOf(it) },
        ).takeUnless { it.isEmpty() },
        claimCode = this[PeopleTable.claimCode],
    )

    /** A person's participations (newest season first), joining team + congregation names. */
    private fun participationsOf(personId: String): List<ParticipationDto> =
        (ParticipantsTable innerJoin RegistrationsTable innerJoin CongregationsTable)
            .join(TeamsTable, JoinType.LEFT, onColumn = ParticipantsTable.teamId, otherColumn = TeamsTable.id)
            .selectAll()
            .where { ParticipantsTable.personId eq personId }
            .map { row ->
                ParticipationDto(
                    id = row[ParticipantsTable.id],
                    seasonYear = row[ParticipantsTable.seasonYear].toString(),
                    congregationId = row[RegistrationsTable.congregationId],
                    congregationName = row[CongregationsTable.name],
                    isContestant = row[ParticipantsTable.isContestant],
                    isCoach = row[ParticipantsTable.isCoach],
                    teamId = row.getOrNull(TeamsTable.id),
                    teamName = row.getOrNull(TeamsTable.name),
                    shirtSize = row[ParticipantsTable.shirtSize]?.let { ShirtSize.valueOf(it) },
                    positions = decodePositions(row[ParticipantsTable.positions]),
                    tribeLeaderWilling = row[ParticipantsTable.tribeLeader],
                    testerId = row[ParticipantsTable.testerId],
                )
            }
            .sortedByDescending { it.seasonYear }

    override fun find(congregationId: String, seasonYear: String): RegistrationDto? = transaction(db) {
        regRow(congregationId, seasonYear)?.toDto()
    }

    /** The registration's row id, creating the draft registration on first use. */
    private fun regIdFor(congregationId: String, seasonYear: String): String =
        regRow(congregationId, seasonYear)?.get(RegistrationsTable.id) ?: run {
            val year = seasonYear.toInt()
            ensureSeasonRow(year)
            val newId = UUID.randomUUID().toString()
            RegistrationsTable.insert {
                it[id] = newId
                it[RegistrationsTable.congregationId] = congregationId
                it[RegistrationsTable.seasonYear] = year
                it[status] = RegistrationStatus.DRAFT.name
                it[siteId] = resolveSeasonSite(year, null) // single-site auto-pin
                it[updatedAtEpochMs] = System.currentTimeMillis()
            }
            newId
        }

    private fun seasonYearInt(regId: String): Int = regRowById(regId)[RegistrationsTable.seasonYear]
    private fun regRowById(regId: String): ResultRow =
        RegistrationsTable.selectAll().where { RegistrationsTable.id eq regId }.single()
    private fun congregationIdFor(regId: String): String = regRowById(regId)[RegistrationsTable.congregationId]

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
        ParticipantsTable.update({ ParticipantsTable.teamId eq teamId }) { it[ParticipantsTable.teamId] = null }
        TeamsTable.deleteWhere { TeamsTable.id eq teamId } > 0
    }

    override fun addMember(teamId: String, req: UpsertRosterEntryRequest): AddMemberResult = transaction(db) {
        val team = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
            ?: return@transaction AddMemberResult.TeamNotFound
        val size = ParticipantsTable.selectAll().where { ParticipantsTable.teamId eq teamId }.count()
        if (size >= MAX_TEAM_SIZE) return@transaction AddMemberResult.RosterFull
        val regId = team[TeamsTable.registrationId]
        val year = seasonYearInt(regId)
        val (personId, firstYear) = findOrCreatePerson(congregationIdFor(regId), req, year)
        val participantId = UUID.randomUUID().toString()
        ParticipantsTable.insert {
            it[id] = participantId
            it[ParticipantsTable.personId] = personId
            it[registrationId] = regId
            it[seasonYear] = year
            it[isContestant] = true
            it[ParticipantsTable.teamId] = teamId
            it[shirtSize] = req.shirtSize.name
        }
        touch(regId)
        AddMemberResult.Added(participantEntry(participantId)!!.copy(firstSeasonYear = firstYear?.toString()))
    }

    /**
     * Finds the person for `(congregation, name, birthdate)` — reused across seasons — or creates
     * one, refreshing name/gender. A person already participating in [seasonYear] is skipped (one
     * participation per person per season is DB-enforced), so a same-season, same-name coach edit
     * makes a distinct person rather than colliding. Returns the person id and resolved first
     * season year (earliest once they've competed before, else this season when inexperienced).
     * "Belongs to a congregation" = has any participation under one of that congregation's
     * registrations; people are global now, so this is the query, not a stored scope.
     */
    private fun findOrCreatePerson(
        congregationId: String,
        req: UpsertRosterEntryRequest,
        seasonYear: Int,
        exceptParticipantId: String? = null,
    ): Pair<String, Int?> {
        val trimmed = req.name.trim()
        val bd = req.birthdate?.let(LocalDate::parse)
        val candidates = congregationPeople(congregationId)
            .filter { it[PeopleTable.name].equals(trimmed, ignoreCase = true) }
        val existing = candidates.firstOrNull {
            it[PeopleTable.birthdate] == bd && notEnrolled(it[PeopleTable.id], seasonYear, exceptParticipantId)
        } ?: candidates.firstOrNull {
            // A workbook-seeded youth (no birthdate yet) re-added by name WITH a birthdate is the
            // same person — adopt the birthdate instead of creating a duplicate.
            it[PeopleTable.birthdate] == null && it[PeopleTable.graduationYear] != null &&
                notEnrolled(it[PeopleTable.id], seasonYear, exceptParticipantId)
        }?.also { seeded ->
            PeopleTable.update({ PeopleTable.id eq seeded[PeopleTable.id] }) { it[birthdate] = bd }
        }
        if (existing != null) {
            val personId = existing[PeopleTable.id]
            val hasPriorSeason = ParticipantsTable.selectAll()
                .where { (ParticipantsTable.personId eq personId) and (ParticipantsTable.seasonYear less seasonYear) }
                .any()
            val firstYear = if (hasPriorSeason) existing[PeopleTable.firstSeasonYear]
                else seasonYear.takeIf { req.inexperienced }
            PeopleTable.update({ PeopleTable.id eq personId }) {
                it[name] = trimmed
                it[gender] = req.gender.name
                it[firstSeasonYear] = firstYear
            }
            return personId to firstYear
        }
        val newId = UUID.randomUUID().toString()
        val firstYear = seasonYear.takeIf { req.inexperienced }
        PeopleTable.insert {
            it[id] = newId
            it[name] = trimmed
            it[birthdate] = bd
            it[isAdult] = false
            it[gender] = req.gender.name
            it[firstSeasonYear] = firstYear
            it[claimCode] = freshPersonClaimCode()
        }
        return newId to firstYear
    }

    /** People with any participation under a registration of [congregationId] (global-person query). */
    private fun congregationPeople(congregationId: String): List<ResultRow> =
        (PeopleTable innerJoin ParticipantsTable)
            .join(RegistrationsTable, JoinType.INNER, onColumn = ParticipantsTable.registrationId, otherColumn = RegistrationsTable.id)
            .selectAll()
            .where { RegistrationsTable.congregationId eq congregationId }
            .distinctBy { it[PeopleTable.id] }

    /** True when [personId] has no participation in [seasonYear] (excluding [exceptParticipantId]). */
    private fun notEnrolled(personId: String, seasonYear: Int, exceptParticipantId: String?): Boolean =
        ParticipantsTable.selectAll()
            .where {
                (ParticipantsTable.personId eq personId) and (ParticipantsTable.seasonYear eq seasonYear) and
                    (exceptParticipantId?.let { ParticipantsTable.id neq it } ?: booleanTrue())
            }
            .none()

    private fun booleanTrue() = ParticipantsTable.id.isNotNull()

    /** Drops a person once no participation references them AND no account is linked/managing. */
    private fun prunePersonIfOrphaned(personId: String) {
        val used = ParticipantsTable.selectAll().where { ParticipantsTable.personId eq personId }.any() ||
            UsersTable.selectAll().where { UsersTable.personId eq personId }.any() ||
            CheckoutDutiesTable.selectAll().where { CheckoutDutiesTable.personId eq personId }.any()
        if (!used) PeopleTable.deleteWhere { PeopleTable.id eq personId }
    }

    override fun updateMember(memberId: String, req: UpsertRosterEntryRequest): RosterEntryDto? = transaction(db) {
        val row = ParticipantsTable.selectAll().where { ParticipantsTable.id eq memberId }.singleOrNull()
            ?: return@transaction null
        val regId = row[ParticipantsTable.registrationId]
        val year = row[ParticipantsTable.seasonYear]
        val previousPerson = row[ParticipantsTable.personId]
        val (personId, _) = findOrCreatePerson(congregationIdFor(regId), req, year, exceptParticipantId = memberId)
        ParticipantsTable.update({ ParticipantsTable.id eq memberId }) {
            it[shirtSize] = req.shirtSize.name
            it[ParticipantsTable.personId] = personId
        }
        if (previousPerson != personId) prunePersonIfOrphaned(previousPerson)
        participantEntry(memberId)
    }

    override fun deleteMember(memberId: String): Boolean = transaction(db) {
        val personId = ParticipantsTable.selectAll().where { ParticipantsTable.id eq memberId }
            .singleOrNull()?.get(ParticipantsTable.personId)
        val deleted = ParticipantsTable.deleteWhere { ParticipantsTable.id eq memberId } > 0
        if (deleted && personId != null) prunePersonIfOrphaned(personId)
        deleted
    }

    override fun assignMemberToTeam(memberId: String, teamId: String?): AssignResult = transaction(db) {
        val member = ParticipantsTable.selectAll().where { ParticipantsTable.id eq memberId }.singleOrNull()
            ?: return@transaction AssignResult.MemberNotFound
        val current = member[ParticipantsTable.teamId]
        if (teamId == null) {
            if (current != null) {
                ParticipantsTable.update({ ParticipantsTable.id eq memberId }) { it[ParticipantsTable.teamId] = null }
            }
            return@transaction AssignResult.Assigned
        }
        if (current == teamId) return@transaction AssignResult.Assigned // already there
        val team = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
            ?: return@transaction AssignResult.TeamNotFound
        // Combo teams: any team in the member's season qualifies, own congregation's or not.
        val sameSeason = seasonYearInt(team[TeamsTable.registrationId]) == member[ParticipantsTable.seasonYear]
        if (!sameSeason) return@transaction AssignResult.TeamNotFound
        val size = ParticipantsTable.selectAll().where { ParticipantsTable.teamId eq teamId }.count()
        if (size >= MAX_TEAM_SIZE) return@transaction AssignResult.RosterFull
        ParticipantsTable.update({ ParticipantsTable.id eq memberId }) { it[ParticipantsTable.teamId] = teamId }
        AssignResult.Assigned
    }

    override fun addIndividual(
        congregationId: String,
        seasonYear: String,
        req: UpsertIndividualRequest,
    ): RosterEntryDto = transaction(db) {
        val regId = regIdFor(congregationId, seasonYear)
        val year = seasonYear.toInt()
        val personId = findOrCreateAdultPerson(congregationId, req.name, req.gender, year)
        val claimed = personClaimed(personId)
        val participantId = UUID.randomUUID().toString()
        ParticipantsTable.insert {
            it[id] = participantId
            it[ParticipantsTable.personId] = personId
            it[registrationId] = regId
            it[ParticipantsTable.seasonYear] = year
            it[isContestant] = true
            it[shirtSize] = req.shirtSize.name
            it[tribeLeader] = req.tribeLeaderWilling
        }
        touch(regId)
        RosterEntryDto(
            participantId, req.name.trim(), birthdate = null,
            shirtSize = req.shirtSize, gender = req.gender,
            claimCode = personClaimCode(personId), claimed = claimed,
            tribeLeaderWilling = req.tribeLeaderWilling,
        )
    }

    /** Finds or creates the birthdate-less adult person for `(congregation, name)`. */
    private fun findOrCreateAdultPerson(
        congregationId: String,
        rawName: String,
        gender: Gender,
        seasonYear: Int,
        exceptParticipantId: String? = null,
    ): String {
        val trimmed = rawName.trim()
        val existing = congregationPeople(congregationId).firstOrNull {
            it[PeopleTable.name].equals(trimmed, ignoreCase = true) &&
                it[PeopleTable.birthdate] == null && it[PeopleTable.graduationYear] == null &&
                notEnrolled(it[PeopleTable.id], seasonYear, exceptParticipantId)
        }
        if (existing != null) {
            val personId = existing[PeopleTable.id]
            PeopleTable.update({ PeopleTable.id eq personId }) {
                it[name] = trimmed
                it[PeopleTable.gender] = gender.name
            }
            return personId
        }
        val newId = UUID.randomUUID().toString()
        PeopleTable.insert {
            it[id] = newId
            it[name] = trimmed
            it[birthdate] = null
            it[isAdult] = true
            it[PeopleTable.gender] = gender.name
            it[claimCode] = freshPersonClaimCode()
        }
        return newId
    }

    override fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RosterEntryDto? =
        transaction(db) {
            val row = ParticipantsTable.selectAll().where { ParticipantsTable.id eq individualId }.singleOrNull()
                ?: return@transaction null
            val previousPerson = row[ParticipantsTable.personId]
            val year = row[ParticipantsTable.seasonYear]
            val personId = findOrCreateAdultPerson(
                congregationIdFor(row[ParticipantsTable.registrationId]), req.name, req.gender, year,
                exceptParticipantId = individualId,
            )
            ParticipantsTable.update({ ParticipantsTable.id eq individualId }) {
                it[shirtSize] = req.shirtSize.name
                it[ParticipantsTable.personId] = personId
                it[tribeLeader] = req.tribeLeaderWilling
            }
            if (previousPerson != personId) prunePersonIfOrphaned(previousPerson)
            participantEntry(individualId)
        }

    override fun deleteIndividual(individualId: String): Boolean = deleteMember(individualId)

    override fun addGuest(congregationId: String, seasonYear: String, req: UpsertGuestRequest): GuestDto =
        transaction(db) {
            val regId = regIdFor(congregationId, seasonYear)
            val year = seasonYear.toInt()
            val personId = createGuestPerson(req)
            val participantId = UUID.randomUUID().toString()
            ParticipantsTable.insert {
                it[id] = participantId
                it[ParticipantsTable.personId] = personId
                it[registrationId] = regId
                it[ParticipantsTable.seasonYear] = year
                it[isContestant] = false
                it[shirtSize] = req.shirtSize?.name
                it[positions] = encodePositions(req.positions)
                it[tribeLeader] = req.tribeLeaderWilling
            }
            touch(regId)
            GuestDto(
                participantId, req.name.trim(), req.shirtSize, req.birthdate, req.gender,
                positions = req.positions, tribeLeaderWilling = req.tribeLeaderWilling, contact = req.contact,
            )
        }

    /** A guest is a fresh person carrying the guest's identity + contact (guests have no account). */
    private fun createGuestPerson(req: UpsertGuestRequest): String {
        val personId = UUID.randomUUID().toString()
        PeopleTable.insert {
            it[id] = personId
            it[name] = req.name.trim()
            it[birthdate] = req.birthdate?.let(LocalDate::parse)
            it[isAdult] = req.birthdate == null
            it[gender] = req.gender?.name
            it[email] = req.contact?.email?.trim()?.ifBlank { null }
            it[contactAddress] = req.contact?.address?.trim() ?: ""
            it[contactCity] = req.contact?.city?.trim() ?: ""
            it[contactState] = req.contact?.state?.trim() ?: ""
            it[contactZip] = req.contact?.zip?.trim() ?: ""
            it[contactPhone] = req.contact?.phone?.trim() ?: ""
            it[contactPreference] = req.contact?.preference?.name
            it[claimCode] = freshPersonClaimCode()
        }
        return personId
    }

    override fun updateGuest(guestId: String, req: UpsertGuestRequest): GuestDto? = transaction(db) {
        val row = ParticipantsTable.selectAll().where { ParticipantsTable.id eq guestId }.singleOrNull()
            ?: return@transaction null
        val personId = row[ParticipantsTable.personId]
        PeopleTable.update({ PeopleTable.id eq personId }) {
            it[name] = req.name.trim()
            it[birthdate] = req.birthdate?.let(LocalDate::parse)
            it[isAdult] = req.birthdate == null
            it[gender] = req.gender?.name
            it[email] = req.contact?.email?.trim()?.ifBlank { null }
            it[contactAddress] = req.contact?.address?.trim() ?: ""
            it[contactCity] = req.contact?.city?.trim() ?: ""
            it[contactState] = req.contact?.state?.trim() ?: ""
            it[contactZip] = req.contact?.zip?.trim() ?: ""
            it[contactPhone] = req.contact?.phone?.trim() ?: ""
            it[contactPreference] = req.contact?.preference?.name
        }
        ParticipantsTable.update({ ParticipantsTable.id eq guestId }) {
            it[shirtSize] = req.shirtSize?.name
            it[positions] = encodePositions(req.positions)
            it[tribeLeader] = req.tribeLeaderWilling
        }
        guestEntry(guestId)
    }

    override fun deleteGuest(guestId: String): Boolean = deleteMember(guestId)

    override fun setSite(congregationId: String, seasonYear: String, siteId: String): RegistrationDto =
        transaction(db) {
            val regId = regIdFor(congregationId, seasonYear)
            RegistrationsTable.update({ RegistrationsTable.id eq regId }) {
                it[RegistrationsTable.siteId] = resolveSeasonSite(seasonYear.toInt(), siteId)
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

    // The permission-scoping lookups run before every mutation — single join queries, not two hops.

    override fun congregationIdForTeam(teamId: String): String? = transaction(db) {
        (TeamsTable innerJoin RegistrationsTable).selectAll()
            .where { TeamsTable.id eq teamId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)
    }

    private fun congregationIdForParticipant(participantId: String): String? =
        (ParticipantsTable innerJoin RegistrationsTable).selectAll()
            .where { ParticipantsTable.id eq participantId }
            .singleOrNull()?.get(RegistrationsTable.congregationId)

    override fun congregationIdForMember(memberId: String): String? = transaction(db) {
        congregationIdForParticipant(memberId)
    }

    override fun congregationIdForIndividual(individualId: String): String? = transaction(db) {
        congregationIdForParticipant(individualId)
    }

    override fun congregationIdForGuest(guestId: String): String? = transaction(db) {
        congregationIdForParticipant(guestId)
    }

    override fun contestantIdForMember(memberId: String): String? = transaction(db) {
        ParticipantsTable.selectAll().where { ParticipantsTable.id eq memberId }
            .singleOrNull()?.get(ParticipantsTable.personId)
    }

    override fun returningContestants(congregationId: String, seasonYear: String): List<ReturningContestantDto> =
        transaction(db) {
            val year = seasonYear.toIntOrNull() ?: return@transaction emptyList()
            // Everyone with a *contestant* participation at this congregation, minus those already
            // enrolled this season. People are global, but returning candidates are congregation-
            // scoped by their contestant history there.
            val history = (PeopleTable innerJoin ParticipantsTable)
                .join(RegistrationsTable, JoinType.INNER, onColumn = ParticipantsTable.registrationId, otherColumn = RegistrationsTable.id)
                .selectAll()
                .where { (RegistrationsTable.congregationId eq congregationId) and (ParticipantsTable.isContestant eq true) }
                .toList()
            val enrolledThisSeason = history
                .filter { it[ParticipantsTable.seasonYear] == year }
                .map { it[PeopleTable.id] }
                .toSet()
            val people = history.map { it[PeopleTable.id] }.toSet() - enrolledThisSeason
            if (people.isEmpty()) return@transaction emptyList()
            // Latest contestant enrollment per person (any congregation), for shirt-size prefill.
            val latest = HashMap<String, Pair<Int, String?>>() // person id -> (season, shirt)
            ParticipantsTable.selectAll()
                .where { (ParticipantsTable.personId inList people) and (ParticipantsTable.isContestant eq true) }
                .forEach {
                    val pid = it[ParticipantsTable.personId]
                    val season = it[ParticipantsTable.seasonYear]
                    val current = latest[pid]
                    if (current == null || season > current.first) latest[pid] = season to it[ParticipantsTable.shirtSize]
                }
            PeopleTable.selectAll().where { PeopleTable.id inList people }
                .orderBy(PeopleTable.name)
                .map { row ->
                    val recent = latest[row[PeopleTable.id]]
                    ReturningContestantDto(
                        contestantId = row[PeopleTable.id],
                        name = row[PeopleTable.name],
                        birthdate = row[PeopleTable.birthdate]?.toString(),
                        gender = row[PeopleTable.gender]?.let { Gender.valueOf(it) },
                        lastSeasonYear = recent?.first?.toString(),
                        lastShirtSize = recent?.second?.let { ShirtSize.valueOf(it) },
                        firstSeasonYear = row[PeopleTable.firstSeasonYear]?.toString(),
                        graduationYear = row[PeopleTable.graduationYear],
                    )
                }
        }

    override fun returningContestant(
        congregationId: String,
        seasonYear: String,
        contestantId: String,
    ): ReturningContestantDto? = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction null
        val hasHistory = (ParticipantsTable innerJoin RegistrationsTable).selectAll()
            .where {
                (ParticipantsTable.personId eq contestantId) and (ParticipantsTable.isContestant eq true) and
                    (RegistrationsTable.congregationId eq congregationId)
            }
            .any()
        if (!hasHistory) return@transaction null
        val enrolled = ParticipantsTable.selectAll()
            .where { (ParticipantsTable.personId eq contestantId) and (ParticipantsTable.seasonYear eq year) }
            .any()
        if (enrolled) return@transaction null
        val row = PeopleTable.selectAll().where { PeopleTable.id eq contestantId }.singleOrNull()
            ?: return@transaction null
        ReturningContestantDto(
            contestantId = row[PeopleTable.id],
            name = row[PeopleTable.name],
            birthdate = row[PeopleTable.birthdate]?.toString(),
            gender = row[PeopleTable.gender]?.let { Gender.valueOf(it) },
            firstSeasonYear = row[PeopleTable.firstSeasonYear]?.toString(),
            graduationYear = row[PeopleTable.graduationYear],
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
        allowNewCongregation: Boolean,
    ): EnrollResult = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction EnrollResult.ContestantNotFound
        // Coaches may only enroll their own congregation's returning contestants; the registrar
        // cross-congregation attach ([allowNewCongregation]) waives that history requirement.
        val hasHistory = allowNewCongregation || (ParticipantsTable innerJoin RegistrationsTable).selectAll()
            .where {
                (ParticipantsTable.personId eq contestantId) and (ParticipantsTable.isContestant eq true) and
                    (RegistrationsTable.congregationId eq congregationId)
            }
            .any()
        if (!hasHistory) return@transaction EnrollResult.ContestantNotFound
        val person = PeopleTable.selectAll().where { PeopleTable.id eq contestantId }.singleOrNull()
            ?: return@transaction EnrollResult.ContestantNotFound
        // Eligibility goes by the stored identity — a seeded youth is judged by graduation year
        // even though the real birthdate is adopted just below.
        val candidate = ReturningContestantDto(
            contestantId = person[PeopleTable.id],
            name = person[PeopleTable.name],
            birthdate = person[PeopleTable.birthdate]?.toString(),
            graduationYear = person[PeopleTable.graduationYear],
        )
        if (!eligible(candidate)) return@transaction EnrollResult.NotEligible
        var storedBirthdate = person[PeopleTable.birthdate]
        if (storedBirthdate == null && person[PeopleTable.graduationYear] != null) {
            val provided = birthdate ?: return@transaction EnrollResult.BirthdateRequired
            storedBirthdate = LocalDate.parse(provided)
            PeopleTable.update({ PeopleTable.id eq contestantId }) { it[PeopleTable.birthdate] = storedBirthdate }
        }
        val regId = regIdFor(congregationId, seasonYear)
        // An existing same-season participation (e.g. they were entered as a guest) is upgraded to
        // a contestant in place rather than duplicated — the unique (person, season) forbids two.
        val existing = ParticipantsTable.selectAll()
            .where { (ParticipantsTable.personId eq contestantId) and (ParticipantsTable.seasonYear eq year) }
            .singleOrNull()
        if (existing != null) {
            if (existing[ParticipantsTable.isContestant]) return@transaction EnrollResult.AlreadyEnrolled
            val useTeam = if (storedBirthdate != null) teamId else null
            if (useTeam != null) {
                val team = TeamsTable.selectAll().where { TeamsTable.id eq useTeam }.singleOrNull()
                    ?: return@transaction EnrollResult.TeamNotFound
                if (team[TeamsTable.registrationId] != regId) return@transaction EnrollResult.TeamNotFound
                if (ParticipantsTable.selectAll().where { ParticipantsTable.teamId eq useTeam }.count() >= MAX_TEAM_SIZE) {
                    return@transaction EnrollResult.RosterFull
                }
            }
            ParticipantsTable.update({ ParticipantsTable.id eq existing[ParticipantsTable.id] }) {
                it[isContestant] = true
                it[ParticipantsTable.shirtSize] = shirtSize.name
                it[ParticipantsTable.teamId] = useTeam
            }
            touch(regId)
            return@transaction EnrollResult.Enrolled
        }
        // Adults (birthdate-less) enroll teamless; youth optionally on a team.
        val useTeam = if (storedBirthdate == null) null else teamId
        if (useTeam != null) {
            val team = TeamsTable.selectAll().where { TeamsTable.id eq useTeam }.singleOrNull()
                ?: return@transaction EnrollResult.TeamNotFound
            if (team[TeamsTable.registrationId] != regId) return@transaction EnrollResult.TeamNotFound
            if (ParticipantsTable.selectAll().where { ParticipantsTable.teamId eq useTeam }.count() >= MAX_TEAM_SIZE) {
                return@transaction EnrollResult.RosterFull
            }
        }
        ParticipantsTable.insert {
            it[id] = UUID.randomUUID().toString()
            it[personId] = contestantId
            it[registrationId] = regId
            it[ParticipantsTable.seasonYear] = year
            it[isContestant] = true
            it[ParticipantsTable.teamId] = useTeam
            it[ParticipantsTable.shirtSize] = shirtSize.name
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
        val year = seasonYear.toInt()
        if (teamId != null) {
            val teamRegId = TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()
                ?.get(TeamsTable.registrationId) ?: return@transaction null
            if (seasonYearInt(teamRegId) != year) return@transaction null
        }
        val trimmed = member.name.trim()
        // Seed matches the congregation's people by name (any birthdate), like the pre-V2 seeder.
        val existing = congregationPeople(congregationId)
            .firstOrNull { it[PeopleTable.name].equals(trimmed, ignoreCase = true) }
        val personId = existing?.get(PeopleTable.id) ?: UUID.randomUUID().toString()
        val firstSeason =
            if (member.inexperienced) listOfNotNull(existing?.get(PeopleTable.firstSeasonYear), year).min()
            else existing?.get(PeopleTable.firstSeasonYear)
        if (existing == null) {
            PeopleTable.insert {
                it[id] = personId
                it[name] = trimmed
                it[isAdult] = false
                it[gender] = member.gender?.name
                it[firstSeasonYear] = firstSeason
                it[graduationYear] = graduationYearFor(seasonYear, member.grade)
                it[claimCode] = freshPersonClaimCode()
            }
        } else {
            PeopleTable.update({ PeopleTable.id eq personId }) {
                if (existing[PeopleTable.gender] == null) it[gender] = member.gender?.name
                if (existing[PeopleTable.birthdate] == null) {
                    it[graduationYear] = graduationYearFor(seasonYear, member.grade)
                }
                it[firstSeasonYear] = firstSeason
            }
        }
        val enrollment = ParticipantsTable.selectAll()
            .where { (ParticipantsTable.personId eq personId) and (ParticipantsTable.seasonYear eq year) }
            .firstOrNull()
        val participantId: String
        if (enrollment == null) {
            participantId = UUID.randomUUID().toString()
            ParticipantsTable.insert {
                it[id] = participantId
                it[ParticipantsTable.personId] = personId
                it[registrationId] = regId
                it[ParticipantsTable.seasonYear] = year
                it[isContestant] = true
                it[ParticipantsTable.teamId] = null
                it[shirtSize] = member.shirtSize.name
            }
        } else {
            participantId = enrollment[ParticipantsTable.id]
            ParticipantsTable.update({ ParticipantsTable.id eq participantId }) {
                it[isContestant] = true
                it[shirtSize] = member.shirtSize.name
            }
        }
        // (Re)place on the team when there's room; a full team leaves the entry unassigned.
        if (teamId != null && enrollment?.get(ParticipantsTable.teamId) != teamId) {
            val size = ParticipantsTable.selectAll().where { ParticipantsTable.teamId eq teamId }.count()
            if (size < MAX_TEAM_SIZE) {
                ParticipantsTable.update({ ParticipantsTable.id eq participantId }) { it[ParticipantsTable.teamId] = teamId }
            }
        }
        touch(regId)
        participantEntry(participantId)
    }

    override fun listForSeason(seasonYear: String): List<RegistrationDto> = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction emptyList()
        RegistrationsTable.selectAll()
            .where { RegistrationsTable.seasonYear eq year }
            .map { it.toDto() }
    }

    override fun seasonYears(): List<String> = transaction(db) {
        RegistrationsTable.select(RegistrationsTable.seasonYear).withDistinct()
            .map { it[RegistrationsTable.seasonYear].toString() }
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
        val person = PeopleTable.selectAll().where { PeopleTable.claimCode eq code }.singleOrNull()
            ?: return@transaction ClaimResult.NotFound
        val personId = person[PeopleTable.id]
        // Ownership is durable per person since V2: the claim code is the person's, so claiming it
        // makes this account their manager (across seasons). Idempotent for the same account.
        val existingManager = person[PeopleTable.managedByUserId]
        if (existingManager != null && existingManager != userId) return@transaction ClaimResult.AlreadyClaimed
        PeopleTable.update({ PeopleTable.id eq personId }) { it[managedByUserId] = userId }
        // Return their most recent participation as the claimed entry (matches the old contract).
        val latest = ParticipantsTable.selectAll()
            .where { ParticipantsTable.personId eq personId }
            .orderBy(ParticipantsTable.seasonYear to org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .firstOrNull()
            ?: return@transaction ClaimResult.NotFound
        participantEntry(latest[ParticipantsTable.id])?.let { ClaimResult.Claimed(it.copy(claimed = true)) }
            ?: ClaimResult.NotFound
    }

    override fun entryIdsOwnedBy(userId: String): Set<String> = transaction(db) {
        (ParticipantsTable innerJoin PeopleTable).selectAll()
            .where { PeopleTable.managedByUserId eq userId }
            .map { it[ParticipantsTable.id] }
            .toSet()
    }

    override fun participantIdByName(seasonYear: String, name: String): String? = transaction(db) {
        val year = seasonYear.toIntOrNull() ?: return@transaction null
        (ParticipantsTable innerJoin PeopleTable).selectAll()
            .where { (ParticipantsTable.seasonYear eq year) and (PeopleTable.name.lowerCase() eq name.trim().lowercase()) }
            .firstOrNull()?.get(ParticipantsTable.id)
    }

    // -- helpers ------------------------------------------------------------

    private fun personClaimed(personId: String): Boolean =
        PeopleTable.selectAll().where { PeopleTable.id eq personId }
            .singleOrNull()?.get(PeopleTable.managedByUserId) != null

    private fun personClaimCode(personId: String): String =
        PeopleTable.selectAll().where { PeopleTable.id eq personId }.single()[PeopleTable.claimCode]

    private fun regRow(congregationId: String, seasonYear: String): ResultRow? {
        val year = seasonYear.toIntOrNull() ?: return null
        return RegistrationsTable.selectAll()
            .where {
                (RegistrationsTable.congregationId eq congregationId) and
                    (RegistrationsTable.seasonYear eq year)
            }
            .singleOrNull()
    }

    private fun touch(regId: String) {
        RegistrationsTable.update({ RegistrationsTable.id eq regId }) {
            it[updatedAtEpochMs] = System.currentTimeMillis()
        }
    }

    private fun congregationNameForReg(regId: String): String =
        CongregationsTable.selectAll().where { CongregationsTable.id eq congregationIdFor(regId) }
            .singleOrNull()?.get(CongregationsTable.name) ?: "?"

    private fun teamDto(teamId: String): TeamDto? =
        TeamsTable.selectAll().where { TeamsTable.id eq teamId }.singleOrNull()?.let { row ->
            val homeRegId = row[TeamsTable.registrationId]
            TeamDto(
                id = row[TeamsTable.id],
                name = row[TeamsTable.name],
                members = (ParticipantsTable innerJoin PeopleTable).selectAll()
                    .where { ParticipantsTable.teamId eq teamId }
                    .map { memberRow ->
                        val entry = memberRow.toEntry()
                        val memberRegId = memberRow[ParticipantsTable.registrationId]
                        if (memberRegId == homeRegId) entry
                        else entry.copy(
                            congregationId = congregationIdFor(memberRegId),
                            congregationName = congregationNameForReg(memberRegId),
                        )
                    },
            )
        }

    /** One participation (contestant or guest) as a [RosterEntryDto], joining its person. */
    private fun participantEntry(participantId: String): RosterEntryDto? =
        (ParticipantsTable innerJoin PeopleTable).selectAll()
            .where { ParticipantsTable.id eq participantId }.singleOrNull()?.toEntry()

    private fun guestEntry(participantId: String): GuestDto? =
        (ParticipantsTable innerJoin PeopleTable).selectAll()
            .where { ParticipantsTable.id eq participantId }.singleOrNull()?.toGuest()

    /** Builds a roster entry from a joined participant+person row (person is the identity source). */
    private fun ResultRow.toEntry() = RosterEntryDto(
        id = this[ParticipantsTable.id],
        name = this[PeopleTable.name],
        birthdate = this[PeopleTable.birthdate]?.toString(),
        shirtSize = this[ParticipantsTable.shirtSize]?.let { ShirtSize.valueOf(it) } ?: ShirtSize.AM,
        gender = this[PeopleTable.gender]?.let { Gender.valueOf(it) },
        firstSeasonYear = this[PeopleTable.firstSeasonYear]?.toString(),
        claimCode = this[PeopleTable.claimCode],
        claimed = this[PeopleTable.managedByUserId] != null,
        tribeLeaderWilling = this[ParticipantsTable.tribeLeader],
    )

    private fun ResultRow.toGuest() = GuestDto(
        id = this[ParticipantsTable.id],
        name = this[PeopleTable.name],
        shirtSize = this[ParticipantsTable.shirtSize]?.let { ShirtSize.valueOf(it) },
        birthdate = this[PeopleTable.birthdate]?.toString(),
        gender = this[PeopleTable.gender]?.let { Gender.valueOf(it) },
        positions = decodePositions(this[ParticipantsTable.positions]),
        tribeLeaderWilling = this[ParticipantsTable.tribeLeader],
        contact = ContactInfoDto(
            address = this[PeopleTable.contactAddress],
            city = this[PeopleTable.contactCity],
            state = this[PeopleTable.contactState],
            zip = this[PeopleTable.contactZip],
            phone = this[PeopleTable.contactPhone],
            email = this[PeopleTable.email].orEmpty(),
            preference = this[PeopleTable.contactPreference]?.let { ContactPreference.valueOf(it) },
        ).takeUnless { it.isEmpty() },
    )

    private fun ResultRow.toDto(): RegistrationDto {
        val regId = this[RegistrationsTable.id]
        val congId = this[RegistrationsTable.congregationId]
        val cong = CongregationsTable.selectAll().where { CongregationsTable.id eq congId }.singleOrNull()
        val teamRows = TeamsTable.selectAll()
            .where { TeamsTable.registrationId eq regId }
            .orderBy(TeamsTable.sortOrder)
            .toList()
        val teamIds = teamRows.map { it[TeamsTable.id] }.toSet()
        // ONE query for every contestant participation this registration displays: its own members
        // (home team, unassigned, or away on another congregation's team) plus visitors on its
        // teams. The left-joined team row carries the away/visiting team's home registration.
        val memberRows = (ParticipantsTable innerJoin PeopleTable)
            .join(TeamsTable, JoinType.LEFT, onColumn = ParticipantsTable.teamId, otherColumn = TeamsTable.id)
            .selectAll()
            .where {
                // Youth contestants (team-eligible) = a birthdate OR a seeded graduation year; adults
                // (birthdate null AND graduation year null) are the individuals below.
                ParticipantsTable.isContestant.eq(true) and
                    (PeopleTable.birthdate.isNotNull() or PeopleTable.graduationYear.isNotNull()) and
                    ((ParticipantsTable.registrationId eq regId) or (ParticipantsTable.teamId inList teamIds))
            }
            .toList()
        val foreignRegIds = memberRows
            .flatMap { listOfNotNull(it[ParticipantsTable.registrationId], it.getOrNull(TeamsTable.registrationId)) }
            .filter { it != regId }
            .distinct()
        val regCongregation: Map<String, String> = if (foreignRegIds.isEmpty()) emptyMap() else
            RegistrationsTable.selectAll().where { RegistrationsTable.id inList foreignRegIds }
                .associate { it[RegistrationsTable.id] to it[RegistrationsTable.congregationId] }
        val congregationName: Map<String, String> = if (foreignRegIds.isEmpty()) emptyMap() else
            CongregationsTable.selectAll().where { CongregationsTable.id inList regCongregation.values.distinct() }
                .associate { it[CongregationsTable.id] to it[CongregationsTable.name] }

        fun nameForReg(id: String): String = regCongregation[id]?.let { congregationName[it] } ?: "?"
        val homeRows = memberRows.filter { it[ParticipantsTable.registrationId] == regId }
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
            seasonYear = this[RegistrationsTable.seasonYear].toString(),
            status = RegistrationStatus.valueOf(this[RegistrationsTable.status]),
            siteId = this[RegistrationsTable.siteId],
            teams = teamRows.map { teamRow ->
                TeamDto(
                    id = teamRow[TeamsTable.id],
                    name = teamRow[TeamsTable.name],
                    members = memberRows
                        .filter { it[ParticipantsTable.teamId] == teamRow[TeamsTable.id] }
                        .map { memberRow ->
                            val entry = memberRow.toEntry()
                            val memberRegId = memberRow[ParticipantsTable.registrationId]
                            if (memberRegId == regId) entry
                            else entry.copy(
                                congregationId = regCongregation[memberRegId],
                                congregationName = nameForReg(memberRegId),
                            )
                        },
                )
            },
            individuals = (ParticipantsTable innerJoin PeopleTable).selectAll()
                .where {
                    // Adults only: birthdate null AND no seeded graduation year (that would be youth).
                    (ParticipantsTable.registrationId eq regId) and ParticipantsTable.isContestant.eq(true) and
                        PeopleTable.birthdate.isNull() and PeopleTable.graduationYear.isNull()
                }
                .orderBy(PeopleTable.name)
                .map { it.toEntry().copy(birthdate = null) },
            unassigned = homeRows
                .filter { it[ParticipantsTable.teamId] == null }
                .sortedBy { it[PeopleTable.name] }
                .map { it.toEntry() },
            awayMembers = homeRows
                .filter { row -> row[ParticipantsTable.teamId] != null && row[ParticipantsTable.teamId] !in teamIds }
                .sortedBy { it[PeopleTable.name] }
                .map { row ->
                    AwayMemberDto(
                        entry = row.toEntry(),
                        teamId = row[TeamsTable.id],
                        teamName = row[TeamsTable.name],
                        congregationName = nameForReg(row[TeamsTable.registrationId]),
                    )
                },
            guests = (ParticipantsTable innerJoin PeopleTable).selectAll()
                .where { (ParticipantsTable.registrationId eq regId) and (ParticipantsTable.isContestant eq false) }
                .orderBy(PeopleTable.name)
                .map { it.toGuest() },
            submittedAt = this[RegistrationsTable.submittedAtEpochMs]?.let { java.time.Instant.ofEpochMilli(it).toString() },
            paidAt = this[RegistrationsTable.paidAtEpochMs]?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
    }
}
