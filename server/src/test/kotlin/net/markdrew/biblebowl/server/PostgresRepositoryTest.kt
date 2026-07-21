package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.CreateCongregationRequest
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
import net.markdrew.biblebowl.server.data.AddMemberResult
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

    private val available: Boolean by lazy {
        runCatching {
            DriverManager.getConnection(
                "jdbc:postgresql://localhost:5432/biblebowl", "biblebowl", "biblebowl-dev",
            ).close()
        }.isSuccess
    }

    @BeforeTest
    fun cleanTables() {
        if (!available) return
        val db = DatabaseFactory.connect()
        transaction(db) {
            QuestionVotesTable.deleteAll()
            QuestionsTable.deleteAll()
            ScoresTable.deleteAll()
            ScoreReleasesTable.deleteAll()
            TeamMembersTable.deleteAll()
            IndividualsTable.deleteAll()
            RegistrationGuestsTable.deleteAll()
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
        val db = DatabaseFactory.connect()
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
    }

    @Test
    fun questionLifecycleSubmitVoteModerate() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val db = DatabaseFactory.connect()
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
    fun scoreCellsUpsertClearAndReleaseToggle() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val db = DatabaseFactory.connect()
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
        val db = DatabaseFactory.connect()
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
        val db = DatabaseFactory.connect()
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
        val db = DatabaseFactory.connect()
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)
        val registrations = PostgresRegistrationRepository(db)

        val coach = users.create("gcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertIs<CreateCongregationResult.Created>(congregations.create(
            CreateCongregationRequest("Guest Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        )).congregation

        // Adding a guest creates the draft registration, like teams and individuals do.
        val volunteer = registrations.addGuest(cong.id, "2027", UpsertGuestRequest(" Aunt Vol ", ShirtSize.AM))
        assertEquals("Aunt Vol", volunteer.name)
        val child = registrations.addGuest(cong.id, "2027", UpsertGuestRequest("Little Sib", ShirtSize.YS, child = true))
        assertEquals(cong.id, registrations.congregationIdForGuest(volunteer.id))
        assertNull(registrations.congregationIdForGuest("nope"))

        val reg = assertNotNull(registrations.find(cong.id, "2027"))
        assertEquals(listOf(volunteer, child), reg.guests, "name-sorted")

        val edited = assertNotNull(
            registrations.updateGuest(child.id, UpsertGuestRequest("Bigger Sib", ShirtSize.YM, child = false)))
        assertEquals(GuestDto(child.id, "Bigger Sib", ShirtSize.YM, child = false), edited)
        assertNull(registrations.updateGuest("nope", UpsertGuestRequest("X", ShirtSize.AM)))

        assertTrue(registrations.deleteGuest(volunteer.id))
        assertTrue(!registrations.deleteGuest(volunteer.id))
        assertEquals(listOf(edited), assertNotNull(registrations.find(cong.id, "2027")).guests)
    }

    @Test
    fun durableContestantsLinkAndReuseAcrossSeasons() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val db = DatabaseFactory.connect()
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
    fun returningContestantsAndEnrollAcrossSeasons() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val db = DatabaseFactory.connect()
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
        val db = DatabaseFactory.connect()
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
        val db = DatabaseFactory.connect()
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
        val db = DatabaseFactory.connect()
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
}
