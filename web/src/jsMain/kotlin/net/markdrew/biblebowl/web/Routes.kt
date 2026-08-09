package net.markdrew.biblebowl.web

import net.markdrew.biblebowl.api.StudySection

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
    const val ADMIN_MATERIALS = "admin/materials"
    const val ADMIN_REGISTRATIONS = "admin/registrations"
    const val ADMIN_COUNTS = "admin/counts"
    const val ADMIN_HOUSING = "admin/housing"
    const val ADMIN_TRIBES = "admin/tribes"
    const val ADMIN_TESTERS = "admin/testers"
    const val ADMIN_USERS = "admin/users"
    const val ADMIN_MERGE_PEOPLE = "admin/people"
}

/**
 * The Study & Practice section pages live under `#study/<slug>` — one per shared-api
 * [StudySection], mirrored as the children of the navbar's Study & Practice dropdown (hugo.toml)
 * and listed on the Site Map (sitemap.html); keep those two in sync when a section is added or
 * renamed. `#study` itself is the overview page whose cards link to these.
 */
val StudySection.route: String get() = "${Routes.STUDY}/$slug"

/** The section whose page is [route] (`study/<slug>`), or null for any other route. */
fun StudySection.Companion.fromRoute(route: String): StudySection? =
    StudySection.entries.firstOrNull { it.route == route }

/**
 * Study-family destinations reached from the hub (`#study`, the app's Study & Practice page);
 * they anchor breadcrumb grouping (Home › Study & Practice › X). The navbar has a single
 * Study & Practice entry — these are not nav items.
 */
enum class TopDestination(val route: String, val label: String) {
    QUIZ(Routes.QUIZ, "Quiz Me"),
    QUESTIONS(Routes.QUESTIONS, "Community Questions"),
}

/** The top-level parent of [route] (e.g. `questions/new` → QUESTIONS), or null for signin/account/admin. */
fun topDestinationOf(route: String): TopDestination? =
    TopDestination.entries.firstOrNull { route == it.route || route.startsWith("${it.route}/") }

/** Human label for [route], used for breadcrumbs and the document title. */
fun routeLabel(route: String): String = StudySection.fromRoute(route)?.title ?: when (route) {
    Routes.STUDY -> "Study & Practice" // the overview: one card per study-focus section
    Routes.STUDY_INDICES -> "Names & Numbers"
    Routes.STUDY_HEADINGS -> "Headings Browser" // distinct from the Chapter Headings section page
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
    Routes.ADMIN_MATERIALS -> "Study Materials"
    Routes.ADMIN_REGISTRATIONS -> "Registrations"
    Routes.ADMIN_COUNTS -> "Registration Counts"
    Routes.ADMIN_HOUSING -> "Housing"
    Routes.ADMIN_TRIBES -> "Tribes"
    Routes.ADMIN_TESTERS -> "Tester IDs"
    Routes.ADMIN_USERS -> "Users"
    Routes.ADMIN_MERGE_PEOPLE -> "Merge People"
    else -> "Study & Practice"
}
