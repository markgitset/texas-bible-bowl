package net.markdrew.biblebowl.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.CompositionLocalProvider
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
import net.markdrew.biblebowl.api.hasEventWidePermission
import net.markdrew.biblebowl.api.isGlobalAdmin
import net.markdrew.biblebowl.app.navigation.Routes
import net.markdrew.biblebowl.app.navigation.TopDestination
import net.markdrew.biblebowl.app.navigation.topDestinationOf
import net.markdrew.biblebowl.client.TbbApi
import net.markdrew.biblebowl.app.screens.AccountScreen
import net.markdrew.biblebowl.app.screens.AdminCountsScreen
import net.markdrew.biblebowl.app.screens.AdminHousingScreen
import net.markdrew.biblebowl.app.screens.AdminRegistrationsScreen
import net.markdrew.biblebowl.app.screens.AdminTestersScreen
import net.markdrew.biblebowl.app.screens.AdminTribesScreen
import net.markdrew.biblebowl.app.screens.AdminUsersScreen
import net.markdrew.biblebowl.app.screens.AuthScreen
import net.markdrew.biblebowl.app.screens.ContributeScreen
import net.markdrew.biblebowl.app.screens.DownloadsScreen
import net.markdrew.biblebowl.app.screens.EventScreen
import net.markdrew.biblebowl.app.screens.GradingScreen
import net.markdrew.biblebowl.app.screens.HeadingsScreen
import net.markdrew.biblebowl.app.screens.IndexScreen
import net.markdrew.biblebowl.app.screens.ModerateScreen
import net.markdrew.biblebowl.app.screens.MyScoresScreen
import net.markdrew.biblebowl.app.screens.RegisterScreen
import net.markdrew.biblebowl.app.screens.StandingsScreen
import net.markdrew.biblebowl.app.screens.QuestionsScreen
import net.markdrew.biblebowl.app.screens.QuizScreen
import net.markdrew.biblebowl.app.screens.StudyHubScreen
import net.markdrew.biblebowl.app.screens.AdminSeasonScreen
import net.markdrew.biblebowl.api.FALLBACK_SEASON
import net.markdrew.biblebowl.app.ui.LocalSeason
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
    // Season parameters drive the chapter filters and season labels; baked fallback until fetched.
    var season by remember { mutableStateOf(FALLBACK_SEASON) }
    LaunchedEffect(Unit) {
        runCatching { api.currentSeason() }.onSuccess { season = it }
    }

    TbbTheme {
        CompositionLocalProvider(LocalSeason provides season) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppScaffold(
                    api = api,
                    navController = navController,
                    user = user,
                    onUserChange = { user = it },
                    onSeasonChange = { season = it },
                    onNavHostReady = onNavHostReady,
                )
            }
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
    onSeasonChange: (net.markdrew.biblebowl.api.SeasonDto) -> Unit,
    onNavHostReady: suspend (NavHostController) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTop = topDestinationOf(backStackEntry?.destination?.route)

    fun navigateTop(dest: TopDestination) {
        if (dest == currentTop) {
            // Re-selecting the active destination returns to its root (e.g. Study hub from
            // study/headings). Plain navigate-with-restoreState would restore the saved stack —
            // including the sub-screen the user is trying to leave — and visibly do nothing.
            navController.popBackStack(dest.route, inclusive = false)
        } else {
            navController.navigateTopLevel(dest.route)
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
                    AppNavHost(api, navController, user, onUserChange, onSeasonChange, onNavHostReady)
                }
            }
        }
    }
}

/**
 * Standard top-level navigation: one back press from any tab exits via the start destination, and
 * each tab's state survives switching away and back. Exception: navigating to Study (the start
 * destination) never restores state — restoring there resurrects whatever was stacked above it
 * (e.g. Downloads opened from a hub card), which made the Study tab appear to show another tab's
 * screen. The Study button always means the hub.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = route != Routes.STUDY
    }
}

@Composable
private fun AppNavHost(
    api: TbbApi,
    navController: NavHostController,
    user: UserDto?,
    onUserChange: (UserDto?) -> Unit,
    onSeasonChange: (net.markdrew.biblebowl.api.SeasonDto) -> Unit,
    onNavHostReady: suspend (NavHostController) -> Unit,
) {
    // Tapping a gated action while anonymous routes to sign-in; on success we pop back to where
    // the user was (return-to via the back stack).
    val requireSignIn = { navController.navigate(Routes.SIGN_IN) { launchSingleTop = true } }

    NavHost(navController, startDestination = Routes.STUDY) {
        composable(Routes.STUDY) {
            StudyHubScreen(
                // Indices/headings are study sub-screens (plain pushes); quiz and downloads are
                // top-level destinations, so the hub shortcuts switch tabs like the nav bar does —
                // pushing them inside the Study stack corrupts tab state save/restore.
                onOpenIndices = { navController.navigate(Routes.STUDY_INDICES) },
                onOpenHeadings = { navController.navigate(Routes.STUDY_HEADINGS) },
                onOpenQuiz = { navController.navigateTopLevel(Routes.QUIZ) },
                onOpenDownloads = { navController.navigateTopLevel(Routes.DOWNLOADS) },
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
        composable(Routes.EVENT) {
            EventScreen(
                user = user,
                onOpenRegister = { navController.navigate(Routes.REGISTER) },
                onOpenMyScores = { navController.navigate(Routes.MY_SCORES) },
                onOpenGrading = { navController.navigate(Routes.GRADING) },
                onOpenStandings = { navController.navigate(Routes.STANDINGS) },
            )
        }
        // Registration/scoring routes deploy dark behind the season's launch toggles (global
        // admins preview them); permission gates render the sign-in screen in place, mirroring
        // the web shell's feature()/gatedEventWide() — the server enforces both regardless.
        composable(Routes.REGISTER) {
            // Sign-in only, no permission: step 1 is where a signed-in user *becomes* a coach
            // (self-serve congregation creation); a TEAM_MANAGE gate would lock them out of it.
            // The server scope-checks every mutation regardless.
            FeatureGate(LocalSeason.current.registrationEnabled, user) {
                if (user != null) RegisterScreen(api, user)
                else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.MY_SCORES) {
            FeatureGate(LocalSeason.current.gradingEnabled, user) {
                // Sign-in only: the server scopes the response (owned entries + coached rosters).
                if (user != null) MyScoresScreen(api)
                else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.GRADING) {
            FeatureGate(LocalSeason.current.gradingEnabled, user) {
                if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_ENTER)) {
                    GradingScreen(api, user, onOpenStandings = { navController.navigate(Routes.STANDINGS) })
                } else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.STANDINGS) {
            FeatureGate(LocalSeason.current.gradingEnabled, user) {
                if (user != null && hasEventWidePermission(user.roles, Permission.SCORE_VIEW_ALL)) {
                    StandingsScreen(api, onOpenGrading = { navController.navigate(Routes.GRADING) })
                } else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.SIGN_IN) {
            AuthScreen(api, onSignedIn = { signedIn ->
                onUserChange(signedIn)
                navController.popBackStack()
            })
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(
                api = api,
                user = user,
                onUserChange = onUserChange,
                onSignOut = {
                    api.signOut()
                    onUserChange(null)
                    navController.popBackStack(Routes.STUDY, inclusive = false)
                },
                onOpenSeasonAdmin = { navController.navigate(Routes.ADMIN_SEASON) },
                onNavigate = { navController.navigate(it) { launchSingleTop = true } },
            )
        }
        composable(Routes.ADMIN_SEASON) {
            if (user != null && Permission.SEASON_MANAGE in user.permissions) {
                AdminSeasonScreen(api, onSaved = onSeasonChange)
            } else AuthScreen(api, onSignedIn = onUserChange)
        }
        // Event-ops screens, gated exactly like the web shell: registration launch toggle
        // (admin preview included) + an event-wide grant; the server enforces both regardless.
        composable(Routes.ADMIN_REGISTRATIONS) {
            FeatureGate(LocalSeason.current.registrationEnabled, user) {
                if (user != null && hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) {
                    AdminRegistrationsScreen(
                        api, user,
                        onOpenCounts = { navController.navigate(Routes.ADMIN_COUNTS) },
                        onOpenHousing = { navController.navigate(Routes.ADMIN_HOUSING) },
                    )
                } else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.ADMIN_COUNTS) {
            FeatureGate(LocalSeason.current.registrationEnabled, user) {
                if (user != null && hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) {
                    AdminCountsScreen(api, onOpenDesk = { navController.navigate(Routes.ADMIN_REGISTRATIONS) })
                } else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.ADMIN_HOUSING) {
            FeatureGate(LocalSeason.current.registrationEnabled, user) {
                if (user != null && hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) {
                    AdminHousingScreen(api, onOpenDesk = { navController.navigate(Routes.ADMIN_REGISTRATIONS) })
                } else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.ADMIN_TRIBES) {
            FeatureGate(LocalSeason.current.registrationEnabled, user) {
                if (user != null && hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE)) {
                    AdminTribesScreen(api, onOpenDesk = { navController.navigate(Routes.ADMIN_REGISTRATIONS) })
                } else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        // Registrars prep tester IDs/nametags; graders need the ZipGrade export — either works.
        composable(Routes.ADMIN_TESTERS) {
            FeatureGate(LocalSeason.current.registrationEnabled, user) {
                val allowed = user != null &&
                    (hasEventWidePermission(user.roles, Permission.REGISTRATION_MANAGE) ||
                        hasEventWidePermission(user.roles, Permission.SCORE_ENTER))
                if (allowed) AdminTestersScreen(api)
                else AuthScreen(api, onSignedIn = onUserChange)
            }
        }
        composable(Routes.ADMIN_USERS) {
            if (user != null && Permission.USER_MANAGE in user.permissions) {
                AdminUsersScreen(api, currentUser = user, onUserChange = onUserChange)
            } else AuthScreen(api, onSignedIn = onUserChange)
        }
    }

    // Runs after NavHost has set its graph (same subcomposition, effects in composition order).
    LaunchedEffect(navController) { onNavHostReady(navController) }
}

/**
 * Renders [content] only while a season feature is visible — its launch toggle [enabled], or the
 * signed-in user is a global admin previewing the dark-deployed feature. A dark feature shows a
 * launch notice instead, mirroring the web shell's feature() gate.
 */
@Composable
private fun FeatureGate(enabled: Boolean, user: UserDto?, content: @Composable () -> Unit) {
    if (enabled || (user != null && isGlobalAdmin(user.roles))) {
        content()
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Not open yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "This part of the app hasn't opened for the season — check back soon.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
