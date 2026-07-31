package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.model.StudyGuideParser
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Email of the system user that authors seeded study-guide questions (permanently unloginable). */
const val STUDY_GUIDE_SEED_EMAIL = "study-guide@seed.texasbiblebowl.org"

/** Display name of the system user that authors seeded study-guide questions. */
const val STUDY_GUIDE_SEED_NAME = "TBB Study Guide"

private val log: Logger = LoggerFactory.getLogger("net.markdrew.biblebowl.server.SeedStudyGuide")

/**
 * Idempotently seeds the community question bank with the bundled multiple-choice study guide for the
 * current season's study set, so a fresh deployment has FACT_FINDER content (practice tests, flashcards,
 * quiz, exports) before any user has submitted a question.
 *
 * All questions are inserted as APPROVED, authored by a dedicated system user ([STUDY_GUIDE_SEED_EMAIL])
 * whose password hash can never verify, so the account can't be signed into or registered over. Seeding is
 * skipped (returning 0) when that user already has any questions — so it runs once per persistent database,
 * per boot for the in-memory dev store, and an admin rejecting a bad seeded question sticks across restarts.
 * Also skipped, with a log line, when the season's study set can't be resolved or has no bundled
 * `study-guide.tsv` resource.
 *
 * Note: [SubmitQuestionRequest.chapter] is set to the chapter within the book — unambiguous for
 * single-book study sets like Acts; multi-book sets would need a chapter-numbering convention first.
 *
 * @return the number of questions inserted (0 when skipped)
 */
fun seedStudyGuideQuestions(
    users: UserRepository,
    questions: QuestionRepository,
    seasons: SeasonRepository,
): Int {
    val studySetName = seasons.current().studySet
    val studySet = StandardStudySet.parseOrNull(studySetName)
    if (studySet == null) {
        log.warn("Unknown study set '{}' — skipping study-guide seeding", studySetName)
        return 0
    }
    val seedUser = users.findByEmail(STUDY_GUIDE_SEED_EMAIL) ?: users.create(
        email = STUDY_GUIDE_SEED_EMAIL,
        displayName = STUDY_GUIDE_SEED_NAME,
        birthdate = null,
        adult = true,
        // Not a valid Passwords.hash encoding ("iterations:salt:hash"), so verify() always fails.
        passwordHash = "!",
        roles = emptyList(),
    )
    if (questions.countByAuthor(seedUser.id) > 0) return 0
    val guide = StudyGuideParser.loadStudyGuideOrNull(studySet)
    if (guide == null) {
        log.warn("No bundled study guide for study set '{}' — skipping seeding", studySet.simpleName)
        return 0
    }
    val requests = guide.map { q ->
        SubmitQuestionRequest(
            roundType = Round.FACT_FINDER,
            prompt = q.question,
            answer = q.choices[q.correctAnswer],
            // One reference per printed verse ref, e.g. "Acts 1:1" or "Acts 1:4-5". A few guide rows list
            // several verses ("15,34") — split them so each reference stays comma-free (the Postgres
            // references column is comma-joined).
            references = q.verseRefString.split(',').map {
                "${q.chapterRef.book.fullName} ${q.chapterRef.chapter}:${it.trim()}"
            },
            choices = q.choices,
            chapter = q.chapterRef.chapter,
        )
    }
    val inserted = questions.seedApproved(seedUser.id, STUDY_GUIDE_SEED_NAME, requests)
    log.info("Seeded {} study-guide questions for study set '{}'", inserted, studySet.simpleName)
    return inserted
}
