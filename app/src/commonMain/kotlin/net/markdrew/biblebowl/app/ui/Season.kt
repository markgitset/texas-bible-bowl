package net.markdrew.biblebowl.app.ui

import androidx.compose.runtime.compositionLocalOf
import net.markdrew.biblebowl.api.FALLBACK_SEASON

/** The current season, provided at the app root and refreshed from the server on launch. */
val LocalSeason = compositionLocalOf { FALLBACK_SEASON }
