package net.markdrew.biblebowl.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Texas Bible Bowl",
        state = rememberWindowState(width = 520.dp, height = 760.dp),
    ) {
        App()
    }
}
