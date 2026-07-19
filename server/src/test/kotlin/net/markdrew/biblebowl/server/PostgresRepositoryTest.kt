package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.ClaimResult
import net.markdrew.biblebowl.server.data.CongregationsTable
import net.markdrew.biblebowl.server.data.DatabaseFactory
import net.markdrew.biblebowl.server.data.IndividualsTable
import net.markdrew.biblebowl.server.data.PostgresCongregationRepository
import net.markdrew.biblebowl.server.data.PostgresQuestionRepository
import net.markdrew.biblebowl.server.data.PostgresRegistrationRepository
import net.markdrew.biblebowl.server.data.PostgresScoreRepository
import net.markdrew.biblebowl.server.data.PostgresUserRepository
import net.markdrew.biblebowl.server.data.QuestionVotesTable
import net.markdrew.biblebowl.server.data.QuestionsTable
import net.markdrew.biblebowl.server.data.RegistrationsTable
import net.markdrew.biblebowl.server.data.RoleGrantsTable
import net.markdrew.biblebowl.server.data.ScoreReleasesTable
import net.markdrew.biblebowl.server.data.ScoresTable
import net.markdrew.biblebowl.server.data.TeamMembersTable
import net.markdrew.biblebowl.server.data.TeamsTable
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
            TeamsTable.deleteAll()
            IndividualsTable.deleteAll()
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
    fun updatingACongregationPersistsAndKeepsTheState() {
        if (!available) { println("Postgres not reachable — skipping"); return }
        val db = DatabaseFactory.connect()
        val users = PostgresUserRepository(db)
        val congregations = PostgresCongregationRepository(db)

        val coach = users.create("coach@tbb.org", "Coach", null, adult = true,
            passwordHash = Passwords.hash("password123"), roles = listOf(RoleGrant(Role.COACH)))
        val cong = assertNotNull(congregations.create(
            CreateCongregationRequest("First Church", "Austin", state = "TX", mailingAddress = "1 Main St", zip = "78701"),
            coach.id,
        ))
        val other = assertNotNull(congregations.create(
            CreateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "2 Elm St", zip = "75001"),
            coach.id,
        ))

        val updated = assertNotNull(congregations.update(
            cong.id, UpdateCongregationRequest("First Christian Church", "Round Rock", "456 Oak Ave", "78664"),
        ))
        assertEquals("First Christian Church", updated.name)
        assertEquals("Round Rock", updated.city)
        assertEquals("456 Oak Ave", updated.mailingAddress)
        assertEquals("78664", updated.zip)
        assertEquals("TX", updated.state)
        assertEquals(updated, congregations.findById(cong.id))

        // Colliding with the other congregation's name+city is refused; the other stays as it was.
        assertNull(congregations.update(cong.id, UpdateCongregationRequest("Second Church", "Dallas", "9 St", "75001")))
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

        val cong = assertNotNull(congregations.create(
            CreateCongregationRequest("Claim Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        ))
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
}
