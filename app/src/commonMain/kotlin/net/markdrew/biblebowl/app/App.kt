package net.markdrew.biblebowl.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.app.navigation.Routes
import net.markdrew.biblebowl.app.navigation.TopDestination
import net.markdrew.biblebowl.app.navigation.topDestinationOf
import net.markdrew.biblebowl.app.net.TbbApi
import net.markdrew.biblebowl.app.screens.AccountScreen
import net.markdrew.biblebowl.app.screens.AuthScreen
import net.markdrew.biblebowl.app.screens.ContributeScreen
import net.markdrew.biblebowl.app.screens.DownloadsScreen
import net.markdrew.biblebowl.app.screens.EventScreen
import net.markdrew.biblebowl.app.screens.HeadingsScreen
import net.markdrew.biblebowl.app.screens.IndexScreen
import net.markdrew.biblebowl.app.screens.ModerateScreen
import net.markdrew.biblebowl.app.screens.QuestionsScreen
import net.markdrew.biblebowl.app.screens.QuizScreen
import net.markdrew.biblebowl.app.screens.StudyHubScreen
import net.markdrew.biblebowl.app.ui.TbbTheme

/**
 * Public-first app shell (docs/gui-redesign.md): no auth wall — everything study-related works
 * anonymously, and signing in only *adds* capabilities. [onNavHostReady] fires once the NavHost
 * has set its graph on the controller, so the wasm entry point can apply the initial URL route
 * and bind browser history — earlier binding races the Scaffold subcomposition and fails.
 */
@Composable
fun App(
    api: TbbApi = remember { TbbApi() },
    navController: NavHostController = rememberNavController(),
    onNavHostReady: suspend (NavHostController) -> Unit = {},
) {
    // The signed-in user is app-level UI state; TbbApi keeps the token for requests.
    var user by remember { mutableStateOf(api.user) }

    TbbTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppScaffold(
                api = api,
                navController = navController,
                user = user,
                onUserChange = { user = it },
                onNavHostReady = onNavHostReady,
            )
        }
    }
}

/**
 * One adaptive scaffold (§4): wide screens get a persistent top bar with text destinations;
 * narrow screens (phones) get a bottom navigation bar. Same five destinations either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    api: TbbApi,
    navController: NavHostController,
    user: UserDto?,
    onUserChange: (UserDto?) -> Unit,
    onNavHostReady: suspend (NavHostController) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTop = topDestinationOf(backStackEntry?.destination?.route)

    fun navigateTop(dest: TopDestination) {
        navController.navigate(dest.route) {
            // Standard top-level navigation: one back press from any tab exits via the start
            // destination, and each tab's state survives switching away and back.
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val accountAction = {
        navController.navigate(if (user == null) Routes.SIGN_IN else Routes.ACCOUNT) {
            launchSingleTop = true
        }
    }

    BoxWithConstraints {
        val compact = maxWidth < 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (compact) currentTop?.label ?: "Texas Bible Bowl" else "Texas Bible Bowl",
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        if (!compact) {
                            Row {
                                TopDestination.entries.forEach { dest ->
                                    TextButton(onClick = { navigateTop(dest) }) {
                                        Text(
                                            dest.label,
                                            fontWeight = if (dest == currentTop) FontWeight.Bold else FontWeight.Normal,
                                            color = if (dest == currentTop) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                        )
                                    }
                                }
                            }
                        }
                        // Quiet account entry point — sign-in is contextual, never a wall (§4).
                        IconButton(onClick = accountAction) {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = if (user == null) "Sign in" else "Account",
                                tint = if (user == null) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            },
            bottomBar = {
                if (compact) {
                    NavigationBar {
                        TopDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = dest == currentTop,
                                onClick = { navigateTop(dest) },
                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp)) {
                    AppNavHost(api, navController, user, onUserChange, onNavHostReady)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    api: TbbApi,
    navController: NavHostController,
    user: UserDto?,
    onUserChange: (UserDto?) -> Unit,
    onNavHostReady: suspend (NavHostController) -> Unit,
) {
    // Tapping a gated action while anonymous routes to sign-in; on success we pop back to where
    // the user was (return-to via the back stack).
    val requireSignIn = { navController.navigate(Routes.SIGN_IN) { launchSingleTop = true } }

    NavHost(navController, startDestination = Routes.STUDY) {
        composable(Routes.STUDY) {
            StudyHubScreen(
                onOpenIndices = { navController.navigate(Routes.STUDY_INDICES) },
                onOpenHeadings = { navController.navigate(Routes.STUDY_HEADINGS) },
                onOpenQuiz = { navController.navigate(Routes.QUIZ) },
                onOpenDownloads = { navController.navigate(Routes.DOWNLOADS) },
            )
        }
        composable(Routes.STUDY_INDICES) { IndexScreen(api) }
        composable(Routes.STUDY_HEADINGS) { HeadingsScreen(api) }
        composable(Routes.QUIZ) { QuizScreen(api) }
        composable(Routes.QUESTIONS) {
            QuestionsScreen(
                api = api,
                user = user,
                onRequireSignIn = requireSignIn,
                onNewQuestion = { navController.navigate(Routes.QUESTIONS_NEW) },
                onModerate = { navController.navigate(Routes.QUESTIONS_MODERATE) },
            )
        }
        composable(Routes.QUESTIONS_NEW) {
            if (user != null && Permission.QUESTION_SUBMIT in user.permissions) ContributeScreen(api)
            else AuthScreen(api, onSignedIn = onUserChange)
        }
        composable(Routes.QUESTIONS_MODERATE) {
            if (user != null && Permission.QUESTION_MODERATE in user.permissions) ModerateScreen(api)
            else AuthScreen(api, onSignedIn = onUserChange)
        }
        composable(Routes.DOWNLOADS) { DownloadsScreen(api) }
        composable(Routes.EVENT) { EventScreen() }
        composable(Routes.SIGN_IN) {
            AuthScreen(api, onSignedIn = { signedIn ->
                onUserChange(signedIn)
                navController.popBackStack()
            })
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(
                user = user,
                onSignOut = {
                    api.signOut()
                    onUserChange(null)
                    navController.popBackStack(Routes.STUDY, inclusive = false)
                },
            )
        }
    }

    // Runs after NavHost has set its graph (same subcomposition, effects in composition order).
    LaunchedEffect(navController) { onNavHostReady(navController) }
}
