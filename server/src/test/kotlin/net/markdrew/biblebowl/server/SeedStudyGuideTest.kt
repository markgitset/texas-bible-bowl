package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SeedStudyGuideTest {

    @Test
    fun seedsTheFullActsStudyGuideAsApprovedFactFinderQuestions() {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()

        val inserted = seedStudyGuideQuestions(users, questions, InMemorySeasonRepository())
        assertEquals(1646, inserted)

        val approved = questions.list(QuestionStatus.APPROVED, chapter = null)
        assertEquals(1646, approved.size)
        assertTrue(approved.all { it.roundType == Round.FACT_FINDER })
        assertTrue(approved.all { it.votes == 0 })

        val seedUser = users.findByEmail(STUDY_GUIDE_SEED_EMAIL)
        assertNotNull(seedUser)
        assertEquals(STUDY_GUIDE_SEED_NAME, seedUser.displayName)
        assertTrue(approved.all { it.authorId == seedUser.id })
        assertTrue(approved.all { it.authorName == STUDY_GUIDE_SEED_NAME })
    }

    @Test
    fun mapsStudyGuideFieldsFaithfully() {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        seedStudyGuideQuestions(users, questions, InMemorySeasonRepository())
        val approved = questions.list(QuestionStatus.APPROVED, chapter = null)

        // The answer is always one of the choices, verbatim (QuizEngine/Kahoot match on equality).
        assertTrue(approved.all { it.choices.isNotEmpty() && it.answer in it.choices })

        // Chapter is always within Acts, and every reference is a comma-free "Acts <ch>:<verse(s)>" string
        // (comma-safe for the Postgres comma-joined references column).
        assertTrue(approved.all { it.chapter in 1..28 })
        val refPattern = Regex("""Acts \d+:\d+(-\d+)?""")
        assertTrue(approved.all { q -> q.references.isNotEmpty() && q.references.all { refPattern.matches(it) } })
        // The study guide contains verse ranges, which must survive comma-splitting intact.
        assertTrue(approved.any { q -> q.references.any { it.contains('-') } })
        // A few guide rows list several verses ("15,34") — those become multiple references.
        assertTrue(approved.any { it.references.size > 1 })

        // First question of the guide comes through verbatim.
        val first = approved.single {
            it.prompt == "In the opening of Acts, to whom does Luke address his account?"
        }
        assertEquals("Theophilus", first.answer)
        assertEquals(listOf("Theophilus", "Timothy", "Titus", "Cornelius"), first.choices)
        assertEquals(1, first.chapter)
        assertEquals(listOf("Acts 1:1"), first.references)
    }

    @Test
    fun secondRunIsANoOp() {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val seasons = InMemorySeasonRepository()

        assertEquals(1646, seedStudyGuideQuestions(users, questions, seasons))
        assertEquals(0, seedStudyGuideQuestions(users, questions, seasons))
        assertEquals(1646, questions.list(null, null).size)
    }

    @Test
    fun aRejectedSeededQuestionStaysRejectedAcrossReRuns() {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val seasons = InMemorySeasonRepository()
        seedStudyGuideQuestions(users, questions, seasons)

        val victim = questions.list(QuestionStatus.APPROVED, chapter = null).first()
        questions.setStatus(victim.id, QuestionStatus.REJECTED)

        assertEquals(0, seedStudyGuideQuestions(users, questions, seasons))
        assertEquals(QuestionStatus.REJECTED, questions.get(victim.id)?.status)
        assertEquals(1646, questions.list(null, null).size)
    }

    @Test
    fun skipsWhenTheSeasonStudySetHasNoBundledGuide() {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val seasons = InMemorySeasonRepository()
        seasons.update(seasons.current().copy(studySet = "luke"))

        assertEquals(0, seedStudyGuideQuestions(users, questions, seasons))
        assertEquals(0, questions.list(null, null).size)
    }

    @Test
    fun theSeedUserCanNeverSignIn() {
        val users = InMemoryUserRepository()
        seedStudyGuideQuestions(users, InMemoryQuestionRepository(), InMemorySeasonRepository())
        val seedUser = users.findByEmail(STUDY_GUIDE_SEED_EMAIL)
        assertNotNull(seedUser)
        assertFalse(Passwords.verify("", seedUser.passwordHash))
        assertFalse(Passwords.verify("!", seedUser.passwordHash))
        assertFalse(Passwords.verify("password123", seedUser.passwordHash))
    }
}
