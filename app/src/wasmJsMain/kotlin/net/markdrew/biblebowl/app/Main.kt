package net.markdrew.biblebowl.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToNavigation
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        // Two-way binding to the browser: routes become hash URLs (shareable/deep-linkable) and the
        // back/forward buttons drive the nav controller. onNavHostReady fires only once the NavHost
        // has set its graph (it lives in a Scaffold subcomposition, so a top-level LaunchedEffect
        // would run too early). bindToNavigation only syncs app -> URL on start, so an initial
        // #route (a pasted deep link or a refresh) must be navigated first or it gets overwritten
        // with the start destination.
        App(onNavHostReady = { navController ->
            val initialRoute = window.location.hash.substringAfter('#', "")
            if (initialRoute.isNotBlank()) {
                runCatching { navController.navigate(initialRoute) } // unknown hash: stay on the hub
            }
            window.bindToNavigation(navController)
        })
    }
}
