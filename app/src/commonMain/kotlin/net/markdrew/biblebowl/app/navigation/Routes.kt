package net.markdrew.biblebowl.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Download
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
    const val SIGN_IN = "signin"
    const val ACCOUNT = "account"
    const val ADMIN_SEASON = "admin/season"
}

/**
 * The five top-level destinations (§2.2). Identical for everyone — role-gated features live
 * *inside* destinations, never as extra tabs. Sized to fit an Android bottom bar exactly.
 */
enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    STUDY(Routes.STUDY, "Study", Icons.AutoMirrored.Filled.MenuBook),
    QUIZ(Routes.QUIZ, "Quiz", Icons.Filled.Quiz),
    QUESTIONS(Routes.QUESTIONS, "Questions", Icons.Filled.Forum),
    DOWNLOADS(Routes.DOWNLOADS, "Downloads", Icons.Filled.Download),
    EVENT(Routes.EVENT, "Event", Icons.Filled.EmojiEvents),
}

/** The destination whose subtree contains [route], e.g. `questions/new` belongs to QUESTIONS. */
fun topDestinationOf(route: String?): TopDestination? =
    TopDestination.entries.firstOrNull { route == it.route || route?.startsWith("${it.route}/") == true }
