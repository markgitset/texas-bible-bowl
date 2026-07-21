package net.markdrew.biblebowl.web

/**
 * Hash-route strings, kept identical to the Compose app's Routes so existing links from the
 * Hugo site (`/app/#study`, `/app/#downloads`, …) keep working unchanged.
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
    const val REGISTER = "event/register"
    const val GRADING = "event/grading"
    const val STANDINGS = "event/standings"
    const val MY_SCORES = "event/my-scores"
    const val SIGN_IN = "signin"
    const val ACCOUNT = "account"
    const val ADMIN_SEASON = "admin/season"
    const val ADMIN_REGISTRATIONS = "admin/registrations"
    const val ADMIN_COUNTS = "admin/counts"
    const val ADMIN_HOUSING = "admin/housing"
    const val ADMIN_TRIBES = "admin/tribes"
    const val ADMIN_TESTERS = "admin/testers"
    const val ADMIN_USERS = "admin/users"
}

/**
 * The top-level app destinations (dropdown items under "Study Resources" in the merged
 * navbar); they anchor breadcrumb grouping and active-state matching. The study hub itself
 * is the Hugo overview page (/study-resources/), not an app route.
 */
enum class TopDestination(val route: String, val label: String) {
    QUIZ(Routes.QUIZ, "Quiz Me"),
    QUESTIONS(Routes.QUESTIONS, "Community Questions"),
    DOWNLOADS(Routes.DOWNLOADS, "Downloads"),
}

/** The top-level parent of [route] (e.g. `questions/new` → QUESTIONS), or null for signin/account/admin. */
fun topDestinationOf(route: String): TopDestination? =
    TopDestination.entries.firstOrNull { route == it.route || route.startsWith("${it.route}/") }

/** Human label for [route], used for breadcrumbs and the document title. */
fun routeLabel(route: String): String = when (route) {
    Routes.STUDY -> "Study Resources" // legacy hash; redirects to the site overview page
    Routes.STUDY_INDICES -> "Names & Numbers"
    Routes.STUDY_HEADINGS -> "Chapter Headings"
    Routes.QUIZ -> "Quiz Me"
    Routes.QUESTIONS -> "Community Questions"
    Routes.QUESTIONS_NEW -> "Submit a Question"
    Routes.QUESTIONS_MODERATE -> "Moderate Questions"
    Routes.DOWNLOADS -> "Downloads"
    Routes.REGISTER -> "Registration"
    Routes.GRADING -> "Grading Desk"
    Routes.STANDINGS -> "Standings"
    Routes.MY_SCORES -> "My Scores"
    Routes.SIGN_IN -> "Sign In"
    Routes.ACCOUNT -> "Account"
    Routes.ADMIN_SEASON -> "Season Settings"
    Routes.ADMIN_REGISTRATIONS -> "Registrations"
    Routes.ADMIN_COUNTS -> "Registration Counts"
    Routes.ADMIN_HOUSING -> "Housing"
    Routes.ADMIN_TRIBES -> "Tribes"
    Routes.ADMIN_TESTERS -> "Tester IDs"
    Routes.ADMIN_USERS -> "Users"
    else -> "Study Resources"
}
