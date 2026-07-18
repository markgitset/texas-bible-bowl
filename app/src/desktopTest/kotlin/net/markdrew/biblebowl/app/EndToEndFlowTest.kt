package net.markdrew.biblebowl.app

import kotlinx.coroutines.runBlocking
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.client.TbbApi
import java.net.HttpURLConnection
import java.net.URI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the exact shared client the UI screens call ([TbbApi]) against a live backend.
 *
 * Requires the server (Postgres mode, ADMIN_EMAIL=admin@tbb.org ADMIN_PASSWORD=admin-secret-123) on
 * localhost:8080; skips (vacuously passes) when it isn't running so CI/unit runs don't need the stack.
 */
class EndToEndFlowTest {

    private val available: Boolean by lazy {
        runCatching {
            val conn = URI("http://localhost:8080/health").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.responseCode == 200.also { conn.disconnect() }
        }.getOrDefault(false)
    }

    @Test
    fun contestantSubmitsAdminApprovesEveryoneStudies() {
        if (!available) { println("Backend not reachable — skipping"); return }
        runBlocking {
            val suffix = Random.nextInt(100_000, 999_999)

            // 1. Contestant registers through the same client the AuthScreen uses.
            val contestantApi = TbbApi()
            val reg = contestantApi.register(
                RegisterRequest("e2e-$suffix@tbb.org", "password123", "E2E Kid $suffix", birthdate = "2013-05-01")
            )
            assertTrue(Permission.QUESTION_SUBMIT in reg.user.permissions)
            assertTrue(contestantApi.isSignedIn)

            // 2. Contestant submits a question (ContributeScreen path).
            val q = contestantApi.submitQuestion(
                SubmitQuestionRequest(
                    roundType = Round.FACT_FINDER,
                    prompt = "E2E-$suffix: Who was chosen to replace Judas?",
                    answer = "Matthias",
                    references = listOf("Acts 1:26"),
                    choices = listOf("Barsabbas", "Matthias", "Barnabas", "Silas", "Stephen"),
                    chapter = 1,
                )
            )
            assertEquals(QuestionStatus.PENDING, q.status)

            // 3. It is NOT visible in the approved list (StudyScreen default).
            assertTrue(contestantApi.questions(chapter = 1).none { it.id == q.id })

            // 4. Admin signs in and sees it in the pending queue (ModerateScreen path).
            val adminApi = TbbApi()
            val admin = adminApi.login(LoginRequest("admin@tbb.org", "admin-secret-123"))
            assertTrue(Permission.QUESTION_MODERATE in admin.user.permissions)
            assertTrue(adminApi.questions(status = QuestionStatus.PENDING).any { it.id == q.id })

            // 5. Admin approves; question appears in Study list; contestant votes on it.
            adminApi.moderate(q.id, QuestionStatus.APPROVED)
            val approved = contestantApi.questions(chapter = 1).firstOrNull { it.id == q.id }
            assertEquals(QuestionStatus.APPROVED, approved?.status)
            val voted = contestantApi.vote(q.id)
            assertEquals(1, voted.votes)

            // 6. Contestant downloads a practice-test PDF for the round (StudyScreen's "Practice PDF" path).
            val pdf = contestantApi.practiceTestPdf(Round.FACT_FINDER, chapter = 1)
            assertTrue(pdf.size > 1000, "PDF should be non-trivial")
            assertEquals("%PDF", pdf.decodeToString(0, 4))

            // 7. And a flashcard deck built from the approved pool.
            val deck = contestantApi.flashcardsPdf(chapter = 1)
            assertTrue(deck.size > 1000, "deck PDF should be non-trivial")
            assertEquals("%PDF", deck.decodeToString(0, 4))
        }
    }
}
