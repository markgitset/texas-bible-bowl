package net.markdrew.biblebowl.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToNavigation
import androidx.navigation.compose.rememberNavController
import kotlinx.browser.document
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        val navController = rememberNavController()
        App(navController = navController)
        // Two-way binding to the browser: routes become hash URLs (shareable/deep-linkable), the
        // back/forward buttons drive the nav controller, and an initial #route deep-links on load.
        LaunchedEffect(Unit) {
            window.bindToNavigation(navController)
        }
    }
}
