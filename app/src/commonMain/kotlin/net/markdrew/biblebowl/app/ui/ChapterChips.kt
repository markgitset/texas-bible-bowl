package net.markdrew.biblebowl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Acts has 28 chapters; drives every chapter filter in the app until the seasons endpoint lands. */
const val ACTS_CHAPTERS = 28

/**
 * The "All + 1..28" chapter filter as a wrapping chip flow. Wrapping (not a horizontally
 * scrolling row) on purpose: 29 chips never fit one line, and horizontal scroll is effectively
 * unusable with a mouse on web/desktop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterChips(selected: Int?, onSelect: (Int?) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
        )
        (1..ACTS_CHAPTERS).forEach { ch ->
            FilterChip(
                selected = selected == ch,
                onClick = { onSelect(if (selected == ch) null else ch) },
                label = { Text("$ch") },
            )
        }
    }
}
