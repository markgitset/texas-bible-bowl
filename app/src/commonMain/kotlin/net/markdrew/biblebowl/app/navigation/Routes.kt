package net.markdrew.biblebowl.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's route strings (see docs/gui-redesign.md §2.1). On web these become hash URLs
 * (`/app#study`, `/app#questions/new`, …) via `window.bindToNavigation`, so they are shareable,
 * deep-linkable, and back/forward-safe.
 */
object Routes {
    const val STUDY = "study"
    const val STUDY_INDICES = "study/indices"
    const val STUDY_HEADINGS = "study/headings"
    const val QUIZ = "quiz"
    const val QUESTIONS = "questions"
    const val QUESTIONS_NEW = "questions/new"
    const val QUESTIONS_MODERATE = "questions/moderate"
    const val DOWNLOADS = "downloads"
    const val EVENT = "event"
    const val REGISTER = "event/register"
    const val MY_SCORES = "event/my-scores"
    const val GRADING = "event/grading"
    const val STANDINGS = "event/standings"
    const val SIGN_IN = "signin"
    const val ACCOUNT = "account"
    const val ADMIN_SEASON = "admin/season"
    const val ADMIN_REGISTRATIONS = "admin/registrations"
    const val ADMIN_COUNTS = "admin/counts"
    const val ADMIN_HOUSING = "admin/housing"
    const val ADMIN_TRIBES = "admin/tribes"
    const val ADMIN_TESTERS = "admin/testers"
    const val ADMIN_USERS = "admin/users"
    const val ADMIN_MERGE_PEOPLE = "admin/people"
}

/**
 * The Study & Practice sections — one screen per study focus at `study/<slug>`, reached from the
 * Study tab's overview cards. Slugs (and thus route strings) are identical to the web app's
 * StudySection (web/.../Routes.kt) so deep links mean the same thing on both platforms — keep
 * the two enums in sync.
 */
enum class StudySection(val slug: String, val title: String) {
    TEXT("the-text", "The Text"),
    GENERAL("general-knowledge", "General Knowledge"),
    HEADINGS("chapter-headings", "Chapter Headings"),
    UNIQUE_WORDS("unique-words", "Unique Words"),
    PRACTICE_TESTS("practice-tests", "Practice Tests"),
    REFERENCE("reference-documents", "Reference Documents"),
    DATA("data-source-files", "Data & Source Files");

    val route: String get() = "${Routes.STUDY}/$slug"
}

/**
 * The four top-level destinations (§2.2). Identical for everyone — role-gated features live
 * *inside* destinations, never as extra tabs. Sized to fit an Android bottom bar exactly.
 * Study is the Study & Practice overview (one card per [StudySection], matching the web app's
 * `#study`); each section is its own screen pushed on the Study stack. The old Downloads tab
 * merged into Study, and `Routes.DOWNLOADS` survives only as a deep-link alias for the overview.
 */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    STUDY(Routes.STUDY, "Study", Icons.AutoMirrored.Filled.MenuBook),
    QUIZ(Routes.QUIZ, "Quiz", Icons.Filled.Quiz),
    QUESTIONS(Routes.QUESTIONS, "Questions", Icons.Filled.Forum),
    EVENT(Routes.EVENT, "Event", Icons.Filled.EmojiEvents),
}

/** The destination whose subtree contains [route], e.g. `questions/new` belongs to QUESTIONS. */
fun topDestinationOf(route: String?): TopDestination? =
    TopDestination.entries.firstOrNull { route == it.route || route?.startsWith("${it.route}/") == true }
