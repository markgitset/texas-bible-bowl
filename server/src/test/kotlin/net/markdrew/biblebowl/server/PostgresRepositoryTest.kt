package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.GuestDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.SeedMemberDto
import net.markdrew.biblebowl.api.UpsertCabinRequest
import net.markdrew.biblebowl.api.UpsertTribeRequest
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.CabinAssignmentsTable
import net.markdrew.biblebowl.server.data.CabinResult
import net.markdrew.biblebowl.server.data.TribeResult
import net.markdrew.biblebowl.server.data.CabinsTable
import net.markdrew.biblebowl.server.data.PendingCoachGrantsTable
import net.markdrew.biblebowl.server.data.CheckoutDutiesTable
import net.markdrew.biblebowl.server.data.PostgresHousingRepository
import net.markdrew.biblebowl.server.data.PostgresTribeRepository
import net.markdrew.biblebowl.server.data.TribeLeadersTable
import net.markdrew.biblebowl.server.data.TribesTable
import net.markdrew.biblebowl.server.data.ClaimResult
import net.markdrew.biblebowl.server.data.CongregationsTable
import net.markdrew.biblebowl.server.data.ContestantsTable
import net.markdrew.biblebowl.server.data.CreateCongregationResult
import net.markdrew.biblebowl.server.data.DatabaseFactory
import net.markdrew.biblebowl.server.data.EnrollResult
import net.markdrew.biblebowl.server.data.IndividualsTable
import net.markdrew.biblebowl.server.data.PostgresCongregationRepository
import net.markdrew.biblebowl.server.data.PostgresQuestionRepository
import net.markdrew.biblebowl.server.data.PostgresRegistrationRepository
import net.markdrew.biblebowl.server.data.PostgresScoreRepository
import net.markdrew.biblebowl.server.data.PostgresTesterIdRepository
import net.markdrew.biblebowl.server.data.TesterIdsTable
import net.markdrew.biblebowl.server.data.PostgresUserRepository
import net.markdrew.biblebowl.server.data.QuestionVotesTable
import net.markdrew.biblebowl.server.data.QuestionsTable
import net.markdrew.biblebowl.server.data.RegistrationGuestsTable
import net.markdrew.biblebowl.server.data.RegistrationsTable
import net.markdrew.biblebowl.server.data.RoleGrantsTable
import net.markdrew.biblebowl.server.data.ScoreReleasesTable
import net.markdrew.biblebowl.server.data.ScoresTable
import net.markdrew.biblebowl.server.data.TeamMembersTable
import net.markdrew.biblebowl.server.data.TeamsTable
import net.markdrew.biblebowl.server.data.UpdateCongregationResult
import net.markdrew.biblebowl.server.data.UsersTable
import net.markdrew.biblebowl.server.security.Passwords
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test against a real Postgres (docker-compose `postgres` service). Skips (vacuously passes)
 * when Postgres isn't reachable so unit-test runs don't require Docker.
 * Run: `docker compose up -d postgres && ./gradlew :server:test`
 */
class PostgresRepositoryTest {

    companion object {
        private val available: Boolean by lazy {
            runCatching {
                DriverManager.getConnection(
                    "jdbc:postgresql://localhost:5432/biblebowl", "biblebowl", "biblebowl-dev",
                ).close()
            }.isSuccess
        }

        // One pool for the whole suite: every DatabaseFactory.connect() builds a fresh Hikari pool
        // (5 eager connections) that nothing ever closes, so per-test connects leak pools until
        // Postgres's connection cap — CI died with "FATAL: sorry, too many clients already".
        private val db by lazy { DatabaseFactory.connect() }
    }

    @BeforeTest
    fun cleanTables() {
        if (!available) return
        transaction(db) {
            QuestionVotesTable.deleteAll()
            QuestionsTable.deleteAll()
            ScoresTable.deleteAll()
            ScoreReleasesTable.deleteAll()
            TesterIdsTable.deleteAll()
            TeamMembersTable.deleteAll()
            IndividualsTable.deleteAll()
            RegistrationGuestsTable.deleteAll()
            CabinAssignmentsTable.deleteAll()
            TribeLeadersTable.deleteAll()
            TribesTable.deleteAll()
            CabinsTable.deleteAll()
            CheckoutDutiesTable.deleteAll()
            PendingCoachGrantsTable.deleteAll()
            ContestantsTable.deleteAll() // after team_members + individuals (both FK-reference it)
            TeamsTable.deleteAll()
            RegistrationsTable.deleteAll()
            CongregationsTable.deleteAll()
            RoleGrantsTable.deleteAll()
            UsersTable.deleteAll()
        }
    }

    @Test
    fun userRoundTripsWithRoleGrants() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)

        val created = users.create(
            email = "Coach@TBB.org", // case-insensitivity check
            displayName = "Coach Paul",
            birthdate = null,
            adult = true,
            passwordHash = Passwords.hash("password123"),
            roles = listOf(RoleGrant(Role.COACH), RoleGrant(Role.CONTESTANT)),
        )
        val byEmail = users.findByEmail("coach@tbb.org")
        assertNotNull(byEmail)
        assertEquals(created.id, byEmail.id)
        assertEquals(setOf(Role.COACH, Role.CONTESTANT), byEmail.roles.map { it.role }.toSet())
        assertEquals(created.id, users.findById(created.id)?.id)

        // Contact info (item 9, F3) persists via updateProfile and reads back; blank clears to null.
        val contact = ContactInfoDto(phone = "555-0100", city = "Waco", preference = ContactPreference.PHONE)
        users.updateProfile(created.id, "Coach Paul", null, adult = true, contact = contact)
        assertEquals(contact, users.findById(created.id)?.contact)
        users.updateProfile(created.id, "Coach Paul", null, adult = true, contact = null)
        assertNull(users.findById(created.id)?.contact)
    }

    @Test
    fun questionLifecycleSubmitVoteModerate() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val questions = PostgresQuestionRepository(db)

        val author = users.create("kid@tbb.org", "Timothy", "2013-05-01", adult = false,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.CONTESTANT)))
        val voter = users.create("friend@tbb.org", "Silas", "2013-06-01", adult = false,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.CONTESTANT)))

        val q = questions.submit(author.id, author.displayName, SubmitQuestionRequest(
            roundType = Round.FIND_THE_VERSE,
            prompt = "\"Repent and be baptized every one of you\"",
            answer = "Acts 2:38",
            references = listOf("ACT2:38"),
            chapter = 2,
        ))
        assertEquals(QuestionStatus.PENDING, q.status)

        // Voting is idempotent per user.
        questions.vote(q.id, voter.id)
        val voted = questions.vote(q.id, voter.id)
        assertEquals(1, voted?.votes)

        // Moderation flips status; approved question appears in filtered list.
        val approved = questions.setStatus(q.id, QuestionStatus.APPROVED)
        assertEquals(QuestionStatus.APPROVED, approved?.status)
        val listed = questions.list(QuestionStatus.APPROVED, chapter = 2)
        assertTrue(listed.any { it.id == q.id })
        assertEquals("Timothy", listed.first { it.id == q.id }.authorName)
        assertTrue(questions.list(QuestionStatus.APPROVED, chapter = 3).none { it.id == q.id })
    }

    @Test
    fun testerIdsAssignOnceAndReadBack() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val testerIds = PostgresTesterIdRepository(db)

        testerIds.assign("2027", "entry-1", 1)
        testerIds.assign("2027", "entry-2", 2)
        testerIds.assign("2028", "entry-1", 5) // seasons are independent
        assertEquals(mapOf("entry-1" to 1, "entry-2" to 2), testerIds.forSeason("2027"))
        assertEquals(mapOf("entry-1" to 5), testerIds.forSeason("2028"))

        // Assignments are permanent: a re-assign of the same entry is ignored.
        testerIds.assign("2027", "entry-1", 99)
        assertEquals(1, testerIds.forSeason("2027")["entry-1"])
    }

    @Test
    fun scoreCellsUpsertClearAndReleaseToggle() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val scores = PostgresScoreRepository(db)

        val grader = users.create("grader@tbb.org", "Grady", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.GRADER)))

        scores.set("entry-1", Round.FIND_THE_VERSE, 38, grader.id)
        scores.set("entry-1", Round.FIND_THE_VERSE, 40, grader.id) // upsert overwrites
        scores.set("entry-1", Round.POWER, 45, grader.id)
        scores.set("entry-2", Round.FIND_THE_VERSE, 22, grader.id)
        assertEquals(
            mapOf(Round.FIND_THE_VERSE to 40, Round.POWER to 45),
            scores.forEntries(listOf("entry-1", "entry-2"))["entry-1"],
        )

        scores.set("entry-1", Round.POWER, null, grader.id) // null clears the cell
        assertEquals(
            mapOf(Round.FIND_THE_VERSE to 40),
            scores.forEntries(listOf("entry-1"))["entry-1"],
        )

        assertNull(scores.releasedAt("2027"))
        val releasedAt = scores.setReleased("2027", grader.id, released = true)
        assertNotNull(releasedAt)
        assertEquals(releasedAt, scores.releasedAt("2027"))
        // Re-releasing keeps the original timestamp; retracting clears it.
        assertEquals(releasedAt, scores.setReleased("2027", grader.id, released = true))
        assertNull(scores.setReleased("2027", grader.id, released = false))
        assertNull(scores.releasedAt("2027"))
    }

    @Test
    fun updatingACongregationPersistsFieldsAndEnforcesCodeUniqueness() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)

        val coach = users.create("coach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("First Church", "Austin", state = "TX", mailingAddress = "1 Main St", zip = "78701"),
            coach.id,
        )).congregation
        val other = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "2 Elm St", zip = "75001"),
            coach.id,
        )).congregation

        val updated = assertIs<UpdateCongregationResult.Updated>(congregations.update(
            cong.id, UpdateCongregationRequest("First Christian Church", "Round Rock", state = "ok", mailingAddress = "456 Oak Ave", zip = "78664", code = "fc"),
        )).congregation
        assertEquals("First Christian Church", updated.name)
        assertEquals("Round Rock", updated.city)
        assertEquals("456 Oak Ave", updated.mailingAddress)
        assertEquals("78664", updated.zip)
        assertEquals("OK", updated.state, "state is editable and uppercased")
        assertEquals("FC", updated.code, "code is uppercased")
        assertEquals(updated, congregations.findById(cong.id))

        // Name+city and code collisions are reported distinctly; the other congregation is untouched.
        assertIs<UpdateCongregationResult.NameCityTaken>(congregations.update(
            cong.id, UpdateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "9 St", zip = "75001")))
        congregations.update(other.id, UpdateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "2 Elm St", zip = "75001", code = "SC"))
        assertIs<UpdateCongregationResult.CodeTaken>(congregations.update(
            cong.id, UpdateCongregationRequest("First Christian Church", "Round Rock", state = "OK", mailingAddress = "456 Oak Ave", zip = "78664", code = "sc")))
        assertEquals("Second Church", congregations.findById(other.id)!!.name)
    }

    @Test
    fun claimLinksARosterEntryToItsOwnerAccount() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("coach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val owner = users.create("owner@tbb.org", "Owner", "2013-05-01", adult = false,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.CONTESTANT)))
        val other = users.create("other@tbb.org", "Other", "2013-06-01", adult = false,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.CONTESTANT)))

        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Claim Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation
        val team = assertNotNull(registrations.addTeam(cong.id, "2027", "Team A"))
        val added = assertIs<AddMemberResult.Added>(
            registrations.addMember(
                team.id,
                UpsertRosterEntryRequest("Kid", birthdate = "2013-05-01", shirtSize = ShirtSize.YL, gender = Gender.FEMALE),
            )
        ).entry

        assertIs<ClaimResult.NotFound>(registrations.claimEntry("ZZZZ9999", owner.id))
        val claimed = assertIs<ClaimResult.Claimed>(registrations.claimEntry(added.claimCode, owner.id))
        assertTrue(claimed.entry.claimed)
        assertIs<ClaimResult.AlreadyClaimed>(registrations.claimEntry(added.claimCode, other.id))
        assertIs<ClaimResult.Claimed>(registrations.claimEntry(added.claimCode, owner.id)) // idempotent re-claim
        assertEquals(setOf(added.id), registrations.entryIdsOwnedBy(owner.id))
        assertTrue(registrations.entryIdsOwnedBy(other.id).isEmpty())
        // The claimed flag round-trips through the full registration read.
        assertTrue(registrations.find(cong.id, "2027")!!.teams.single().members.single().claimed)
    }

    @Test
    fun siteChoicePersistsOnTheRegistration() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val db = DatabaseFactory.connect()
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("scoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Site Church", "Bandera", state = "TX", mailingAddress = "1 Main St", zip = "78003"),
            coach.id,
        )).congregation

        // Pinning the site creates the draft registration, like teams and guests do.
        val pinned = registrations.setSite(cong.id, "2027", "bandina")
        assertEquals("bandina", pinned.siteId)
        assertEquals("bandina", assertNotNull(registrations.find(cong.id, "2027")).siteId)
        // Re-pinning moves it.
        assertEquals("white-river", registrations.setSite(cong.id, "2027", "white-river").siteId)
    }

    @Test
    fun guestsRoundTripOnTheRegistration() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("gcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Guest Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        // Adding a guest creates the draft registration, like teams and individuals do. Adult
        // guests carry their volunteer positions and tribe-leader willingness (backlog F2).
        val volunteer = registrations.addGuest(
            cong.id, "2027",
            UpsertGuestRequest(
                " Aunt Vol ", ShirtSize.AM, gender = Gender.FEMALE,
                positions = listOf("Test Grader", "Kitchen Helper"), tribeLeaderWilling = true,
            ),
        )
        assertEquals("Aunt Vol", volunteer.name)
        assertNull(volunteer.birthdate, "no birthdate collected for adult guests")
        assertEquals(listOf("Test Grader", "Kitchen Helper"), volunteer.positions)
        assertTrue(volunteer.tribeLeaderWilling)
        assertEquals(
            volunteer,
            assertNotNull(registrations.find(cong.id, "2027")).guests.single { it.id == volunteer.id },
            "positions and tribe-leader flag survive the read back",
        )
        val child = registrations.addGuest(
            cong.id, "2027", UpsertGuestRequest("Little Sib", ShirtSize.YS, birthdate = "2020-06-15", gender = Gender.MALE))
        val baby = registrations.addGuest(
            cong.id, "2027", UpsertGuestRequest("Baby Sib", null, birthdate = "2025-06-15", gender = Gender.FEMALE))
        assertNull(baby.shirtSize, "under-3 guests carry no shirt")
        assertEquals(cong.id, registrations.congregationIdForGuest(volunteer.id))
        assertNull(registrations.congregationIdForGuest("nope"))

        val reg = assertNotNull(registrations.find(cong.id, "2027"))
        assertEquals(listOf(volunteer, baby, child), reg.guests, "name-sorted")

        val contact = ContactInfoDto(
            address = "1 Main St", city = "Waco", state = "TX", zip = "76701",
            phone = "555-0100", email = "sib@fam.org", preference = ContactPreference.TEXT,
        )
        val edited = assertNotNull(
            registrations.updateGuest(
                child.id,
                UpsertGuestRequest("Bigger Sib", ShirtSize.YM, birthdate = null, gender = Gender.MALE, contact = contact)))
        assertEquals(
            GuestDto(child.id, "Bigger Sib", ShirtSize.YM, null, Gender.MALE, contact = contact),
            edited)
        assertNull(registrations.updateGuest("nope", UpsertGuestRequest("X", ShirtSize.AM, gender = Gender.MALE)))

        assertTrue(registrations.deleteGuest(volunteer.id))
        assertTrue(!registrations.deleteGuest(volunteer.id))
        assertEquals(listOf(baby, edited), assertNotNull(registrations.find(cong.id, "2027")).guests)

        // Any adult can lead a tribe — the flag round-trips on individual (adult) contestants too.
        val individual = registrations.addIndividual(
            cong.id, "2027", UpsertIndividualRequest("Adult Ace", ShirtSize.AL, Gender.MALE, tribeLeaderWilling = true))
        assertTrue(
            assertNotNull(registrations.find(cong.id, "2027"))
                .individuals.single { it.id == individual.id }.tribeLeaderWilling,
        )
    }

    @Test
    fun durableContestantsLinkAndReuseAcrossSeasons() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("dcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Durable Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        fun add(teamId: String, name: String) = assertIs<AddMemberResult.Added>(
            registrations.addMember(
                teamId,
                UpsertRosterEntryRequest(name, birthdate = "2013-05-01", shirtSize = ShirtSize.YL, gender = Gender.FEMALE),
            )
        ).entry

        val t2027 = assertNotNull(registrations.addTeam(cong.id, "2027", "Team A"))
        val m2027 = add(t2027.id, "Timothy")
        val contestantId = assertNotNull(registrations.contestantIdForMember(m2027.id))

        // Same person next season (case/space-insensitively) → the same durable contestant.
        val t2028 = assertNotNull(registrations.addTeam(cong.id, "2028", "Team A"))
        val m2028 = add(t2028.id, "  timothy ")
        assertEquals(contestantId, registrations.contestantIdForMember(m2028.id))

        // A different person → a distinct contestant.
        val silas = add(t2028.id, "Silas")
        assertTrue(contestantId != registrations.contestantIdForMember(silas.id))

        // The contestant survives while the 2027 enrollment remains, then is pruned once all are gone.
        assertTrue(registrations.deleteMember(m2028.id))
        assertEquals(contestantId, registrations.contestantIdForMember(add(t2028.id, "Timothy").id))
    }

    @Test
    fun comboTeamAssignmentCrossesCongregationsWithinASeason() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("combo@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val home = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Home Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation
        val host = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Host Church", "Waco", state = "TX", mailingAddress = "2 Main St", zip = "76701"),
            coach.id,
        )).congregation

        val homeTeam = assertNotNull(registrations.addTeam(home.id, "2027", "Home Team"))
        val member = assertIs<AddMemberResult.Added>(registrations.addMember(
            homeTeam.id,
            UpsertRosterEntryRequest("Betsy", birthdate = "2013-05-01", shirtSize = ShirtSize.YL, gender = Gender.FEMALE),
        )).entry
        val hostTeam = assertNotNull(registrations.addTeam(host.id, "2027", "Host Team"))

        // A different season's team is off-limits; the same-season foreign team makes a combo team.
        val lastYear = assertNotNull(registrations.addTeam(host.id, "2026", "Old Team"))
        assertIs<net.markdrew.biblebowl.server.data.AssignResult.TeamNotFound>(
            registrations.assignMemberToTeam(member.id, lastYear.id))
        assertIs<net.markdrew.biblebowl.server.data.AssignResult.Assigned>(
            registrations.assignMemberToTeam(member.id, hostTeam.id))

        // Host view: a visiting member labeled with her own congregation.
        val visiting = assertNotNull(registrations.find(host.id, "2027")).teams.single().members.single()
        assertEquals("Betsy", visiting.name)
        assertEquals(home.id, visiting.congregationId)
        assertEquals("Home Church", visiting.congregationName)

        // Home view: still on the books here, listed among the away members.
        val homeReg = assertNotNull(registrations.find(home.id, "2027"))
        val away = homeReg.awayMembers.single()
        assertEquals("Betsy" to "Host Team", away.entry.name to away.teamName)
        assertEquals("Host Church", away.congregationName)
        assertTrue(homeReg.teams.single().members.isEmpty())
        assertTrue(homeReg.unassigned.isEmpty())

        // Unassigning pulls her back into the home pool.
        assertIs<net.markdrew.biblebowl.server.data.AssignResult.Assigned>(
            registrations.assignMemberToTeam(member.id, null))
        assertEquals(listOf("Betsy"), assertNotNull(registrations.find(home.id, "2027")).unassigned.map { it.name })
    }

    @Test
    fun returningContestantsAndEnrollAcrossSeasons() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("rcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Return Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        // A 2026 enrollment → a returning candidate for 2027.
        val old = assertNotNull(registrations.addTeam(cong.id, "2026", "Old Team"))
        registrations.addMember(
            old.id, UpsertRosterEntryRequest("Timothy", birthdate = "2013-05-01", shirtSize = ShirtSize.YM, gender = Gender.MALE),
        )
        val candidates = registrations.returningContestants(cong.id, "2027")
        assertEquals(listOf("Timothy"), candidates.map { it.name })
        assertEquals("2026", candidates.single().lastSeasonYear)
        assertEquals(ShirtSize.YM, candidates.single().lastShirtSize)
        val contestantId = candidates.single().contestantId

        // Enrolling into 2027 creates that season's entry from the durable contestant and clears the candidate.
        val team2027 = assertNotNull(registrations.addTeam(cong.id, "2027", "Team A"))
        assertIs<EnrollResult.Enrolled>(registrations.enrollContestant(cong.id, "2027", contestantId, ShirtSize.YL, team2027.id))
        val member = registrations.find(cong.id, "2027")!!.teams.single().members.single()
        assertEquals("Timothy" to ShirtSize.YL, member.name to member.shirtSize)
        assertEquals(contestantId, registrations.contestantIdForMember(member.id))
        assertTrue(registrations.returningContestants(cong.id, "2027").isEmpty())
        assertIs<EnrollResult.AlreadyEnrolled>(registrations.enrollContestant(cong.id, "2027", contestantId, ShirtSize.YL, null))
    }

    @Test
    fun returningAdultsEnrollAsIndividuals() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("aracoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Adult Return Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        registrations.addIndividual(cong.id, "2027", UpsertIndividualRequest("Pat Adult", ShirtSize.AL, Gender.FEMALE))
        val candidates = registrations.returningContestants(cong.id, "2028")
        assertEquals(listOf("Pat Adult"), candidates.map { it.name })
        assertNull(candidates.single().birthdate, "adults have no birthdate")
        assertEquals("2027", candidates.single().lastSeasonYear)

        // Enrolling a returning adult creates a 2028 individual (not a team member) and clears the candidate.
        assertIs<EnrollResult.Enrolled>(
            registrations.enrollContestant(cong.id, "2028", candidates.single().contestantId, ShirtSize.AM, null),
        )
        assertEquals(listOf("Pat Adult"), registrations.find(cong.id, "2028")!!.individuals.map { it.name })
        assertTrue(registrations.returningContestants(cong.id, "2028").isEmpty())
    }

    @Test
    fun claimingPersistsAcrossSeasons() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("pcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val parent = users.create("pparent@tbb.org", "Parent", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.CONTESTANT)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Persist Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        val t2027 = assertNotNull(registrations.addTeam(cong.id, "2027", "Team A"))
        val m2027 = assertIs<AddMemberResult.Added>(registrations.addMember(
            t2027.id, UpsertRosterEntryRequest("Timothy", birthdate = "2013-05-01", shirtSize = ShirtSize.YM, gender = Gender.MALE),
        )).entry
        val contestantId = registrations.contestantIdForMember(m2027.id)!!

        assertIs<ClaimResult.Claimed>(registrations.claimEntry(m2027.claimCode, parent.id))
        assertEquals(setOf(m2027.id), registrations.entryIdsOwnedBy(parent.id))

        // Enrolling him next season carries the claim forward — no re-claim needed.
        val t2028 = assertNotNull(registrations.addTeam(cong.id, "2028", "Team A"))
        assertIs<EnrollResult.Enrolled>(registrations.enrollContestant(cong.id, "2028", contestantId, ShirtSize.YL, t2028.id))
        val m2028 = registrations.find(cong.id, "2028")!!.teams.single().members.single()
        assertTrue(m2028.claimed, "the new season's entry inherits the durable owner")
        assertEquals(setOf(m2027.id, m2028.id), registrations.entryIdsOwnedBy(parent.id))
    }

    @Test
    fun claimingAnAdultPersistsAcrossSeasons() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("acoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val pat = users.create("apat@tbb.org", "Pat", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.CONTESTANT)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Adult Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        val a2027 = registrations.addIndividual(cong.id, "2027", UpsertIndividualRequest("Pat Adult", ShirtSize.AL, Gender.FEMALE))
        assertIs<ClaimResult.Claimed>(registrations.claimEntry(a2027.claimCode, pat.id))
        assertEquals(setOf(a2027.id), registrations.entryIdsOwnedBy(pat.id))

        // The same adult re-added next season inherits the durable owner (claim persists).
        val a2028 = registrations.addIndividual(cong.id, "2028", UpsertIndividualRequest("Pat Adult", ShirtSize.AL, Gender.FEMALE))
        assertTrue(a2028.claimed, "the new season's adult entry inherits the durable owner")
        assertEquals(setOf(a2027.id, a2028.id), registrations.entryIdsOwnedBy(pat.id))
    }

    @Test
    fun housingCabinsAssignmentsAndDutiesRoundTrip() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val housing = PostgresHousingRepository(db)

        val coach = users.create("hcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Housing Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        // Cabins: add, duplicate-name rejection (same season + site), rename, and season scoping.
        val cabin = assertIs<CabinResult.Ok>(housing.addCabin("2027", UpsertCabinRequest("Bluebonnet", capacity = 12))).cabin
        assertIs<CabinResult.NameTaken>(housing.addCabin("2027", UpsertCabinRequest("bluebonnet")))
        assertIs<CabinResult.Ok>(housing.addCabin("2028", UpsertCabinRequest("Bluebonnet"))) // other season is fine
        val renamed = assertIs<CabinResult.Ok>(housing.updateCabin(cabin.id, UpsertCabinRequest("Bluebonnet Lodge", capacity = 10))).cabin
        assertEquals(10, renamed.capacity)
        assertEquals(listOf("Bluebonnet Lodge"), housing.listCabins("2027").map { it.name })

        // Assignments: a congregation × gender group and an ad-hoc label row, ordered by creation.
        val group = assertNotNull(housing.addAssignment(cabin.id, AddCabinAssignmentRequest(congregationId = cong.id, gender = Gender.MALE)))
        assertNotNull(housing.addAssignment(cabin.id, AddCabinAssignmentRequest(label = "Smith family — RV 3")))
        val rows = housing.listCabins("2027").single().assignments
        assertEquals(listOf(cong.id, null), rows.map { it.congregationId })
        assertEquals(listOf(Gender.MALE, null), rows.map { it.gender })
        assertTrue(housing.deleteAssignment(group.id))

        // Deleting the cabin cascades its remaining assignment rows.
        assertTrue(housing.deleteCabin(cabin.id))
        assertTrue(housing.listCabins("2027").isEmpty())

        // Check-out duty: upsert replaces, blank clears, seasons are independent.
        housing.setDuty("2027", cong.id, "Jane Smith")
        housing.setDuty("2027", cong.id, "John Doe")
        assertEquals(listOf("John Doe"), housing.listDuties("2027").map { it.adultName })
        assertTrue(housing.listDuties("2028").isEmpty())
        housing.setDuty("2027", cong.id, "  ")
        assertTrue(housing.listDuties("2027").isEmpty())
    }

    @Test
    fun workbookSeedRoundTripsGradesAndPendingCoaches() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val admin = users.create("seedadmin@tbb.org", "Admin", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.ADMIN)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Seed Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            admin.id,
        )).congregation

        // Seed a grade-only youth twice — one contestant, one enrollment, grade 4 => class of 2034.
        val team = registrations.addTeam(cong.id, "2026", "Torch Bearers")!!
        val seeded = registrations.seedMember(cong.id, "2026", team.id,
            SeedMemberDto("Grace Grade", Gender.FEMALE, ShirtSize.YM, grade = 4, inexperienced = true))
        assertNotNull(seeded)
        registrations.seedMember(cong.id, "2026", team.id,
            SeedMemberDto("Grace Grade", Gender.FEMALE, ShirtSize.YL, grade = 4, inexperienced = true))
        val reg2026 = registrations.find(cong.id, "2026")!!
        assertEquals(listOf("Grace Grade"), reg2026.teams.single().members.map { it.name })
        assertEquals(ShirtSize.YL, reg2026.teams.single().members.single().shirtSize) // re-run updated in place

        // She's a 2027 returning candidate carrying the graduation year, not a birthdate…
        val candidate = registrations.returningContestants(cong.id, "2027").single { it.name == "Grace Grade" }
        assertNull(candidate.birthdate)
        assertEquals(2034, candidate.graduationYear)
        assertEquals("2026", candidate.firstSeasonYear)

        // …enrolling without a birthdate is refused, with one it lands and sticks.
        assertIs<EnrollResult.BirthdateRequired>(
            registrations.enrollContestant(cong.id, "2027", candidate.contestantId, ShirtSize.YM, null))
        assertIs<EnrollResult.Enrolled>(
            registrations.enrollContestant(cong.id, "2027", candidate.contestantId, ShirtSize.YM, null, "2015-05-01"))
        assertEquals("2015-05-01",
            registrations.find(cong.id, "2027")!!.unassigned.single { it.name == "Grace Grade" }.birthdate)

        // Pending coach grants: add is idempotent, consume drains, unknown emails yield nothing.
        users.addPendingCoachGrant("Coach@Seed.org", cong.id)
        users.addPendingCoachGrant("coach@seed.org", cong.id)
        assertEquals(mapOf("coach@seed.org" to listOf(cong.id)), users.pendingCoachGrants())
        assertEquals(listOf(cong.id), users.consumePendingCoachGrants("COACH@seed.org"))
        assertTrue(users.consumePendingCoachGrants("coach@seed.org").isEmpty())
    }

    @Test
    fun tribesAndLeadersRoundTrip() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val tribes = PostgresTribeRepository(db)

        // Tribes: add, duplicate-name rejection (same season + site), rename, and season scoping.
        val red = assertIs<TribeResult.Ok>(tribes.addTribe("2027", UpsertTribeRequest("Red"))).tribe
        assertIs<TribeResult.NameTaken>(tribes.addTribe("2027", UpsertTribeRequest("red")))
        assertIs<TribeResult.Ok>(tribes.addTribe("2028", UpsertTribeRequest("Red"))) // other season is fine
        assertIs<TribeResult.Ok>(tribes.updateTribe(red.id, UpsertTribeRequest("Red and Yellow Swirl")))
        assertEquals(listOf("Red and Yellow Swirl"), tribes.listTribes("2027").map { it.name })

        // Leaders keep assignment order; delete one, then deleting the tribe cascades the rest.
        val kisha = assertNotNull(tribes.addLeader(red.id, "Kisha Dearlove"))
        assertNotNull(tribes.addLeader(red.id, "Taylor Jones"))
        assertEquals(
            listOf("Kisha Dearlove", "Taylor Jones"),
            tribes.listTribes("2027").single().leaders.map { it.name },
        )
        assertTrue(tribes.deleteLeader(kisha.id))
        assertEquals(listOf("Taylor Jones"), tribes.listTribes("2027").single().leaders.map { it.name })
        assertTrue(tribes.deleteTribe(red.id))
        assertTrue(tribes.listTribes("2027").isEmpty())
    }
}
