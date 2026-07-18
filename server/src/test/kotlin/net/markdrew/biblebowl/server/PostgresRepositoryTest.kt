package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.server.data.DatabaseFactory
import net.markdrew.biblebowl.server.data.PostgresQuestionRepository
import net.markdrew.biblebowl.server.data.PostgresUserRepository
import net.markdrew.biblebowl.server.data.QuestionVotesTable
import net.markdrew.biblebowl.server.data.QuestionsTable
import net.markdrew.biblebowl.server.data.RoleGrantsTable
import net.markdrew.biblebowl.server.data.UsersTable
import net.markdrew.biblebowl.server.security.Passwords
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
