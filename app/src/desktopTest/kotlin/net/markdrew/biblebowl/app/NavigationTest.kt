package net.markdrew.biblebowl.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.markdrew.biblebowl.app.navigation.Routes
import net.markdrew.biblebowl.app.navigation.TopDestination
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tab switching ([navigateTop]) against a real NavController. Screens outside every tab (Account,
 * sign-in) are pushed on whichever tab is showing; they must never be saved into that tab's state
 * and come back on its next selection.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationTest {

    private val routes = listOf(
        Routes.STUDY, Routes.STUDY_HEADINGS, Routes.QUIZ, Routes.QUESTIONS, Routes.QUESTIONS_NEW,
        Routes.EVENT, Routes.GRADING, Routes.SIGN_IN, Routes.ACCOUNT,
    )

    /** Runs [steps] one per idle frame, then returns the route left showing. */
    private fun routeAfter(vararg steps: NavHostController.() -> Unit): String? {
        var shown: String? = null
        runComposeUiTest {
            lateinit var nav: NavHostController
            setContent {
                nav = rememberNavController()
                NavHost(nav, startDestination = Routes.STUDY) {
                    routes.forEach { route -> composable(route) { Text(route) } }
                }
            }
            steps.forEach { step -> runOnIdle { nav.step() } }
            runOnIdle { shown = nav.currentDestination?.route }
        }
        return shown
    }

    @Test
    fun quizTabAfterOpeningAccountFromQuizShowsQuiz() {
        assertEquals(
            Routes.QUIZ,
            routeAfter(
                { navigateTop(TopDestination.QUIZ) },
                { navigate(Routes.ACCOUNT) },
                { navigateTop(TopDestination.EVENT) },
                { navigateTop(TopDestination.QUIZ) },
            ),
        )
    }

    @Test
    fun tappingQuizOnAccountShowsQuiz() {
        assertEquals(
            Routes.QUIZ,
            routeAfter(
                { navigateTop(TopDestination.QUIZ) },
                { navigate(Routes.ACCOUNT) },
                { navigateTop(TopDestination.QUIZ) },
                { navigateTop(TopDestination.QUESTIONS) },
                { navigateTop(TopDestination.QUIZ) },
            ),
        )
    }

    @Test
    fun abandonedSignInIsNotRestoredWithItsTab() {
        assertEquals(
            Routes.QUESTIONS,
            routeAfter(
                { navigateTop(TopDestination.QUESTIONS) },
                { navigate(Routes.SIGN_IN) },
                { navigateTop(TopDestination.STUDY) },
                { navigateTop(TopDestination.QUESTIONS) },
            ),
        )
    }

    @Test
    fun crossTabPushFromAccountIsNotRestoredWithTheTabBeneath() {
        assertEquals(
            Routes.QUIZ,
            routeAfter(
                { navigateTop(TopDestination.QUIZ) },
                { navigate(Routes.ACCOUNT) },
                { navigate(Routes.GRADING) },
                { navigateTop(TopDestination.STUDY) },
                { navigateTop(TopDestination.QUIZ) },
            ),
        )
    }

    @Test
    fun tabSubScreensStillSurviveSwitchingAway() {
        assertEquals(
            Routes.QUESTIONS_NEW,
            routeAfter(
                { navigateTop(TopDestination.QUESTIONS) },
                { navigate(Routes.QUESTIONS_NEW) },
                { navigateTop(TopDestination.EVENT) },
                { navigateTop(TopDestination.QUESTIONS) },
            ),
        )
    }

    @Test
    fun reselectingTheActiveTabReturnsToItsRoot() {
        assertEquals(
            Routes.STUDY,
            routeAfter(
                { navigate(Routes.STUDY_HEADINGS) },
                { navigateTop(TopDestination.STUDY) },
            ),
        )
    }
}
