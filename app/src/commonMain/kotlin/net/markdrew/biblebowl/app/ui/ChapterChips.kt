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


/**
 * The "All + 1..N" chapter filter as a wrapping chip flow (N = the season's chapter count).
 * Wrapping (not a horizontally scrolling row) on purpose: the chips never fit one line, and
 * horizontal scroll is effectively unusable with a mouse on web/desktop.
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
        (1..LocalSeason.current.chapterCount).forEach { ch ->
            FilterChip(
                selected = selected == ch,
                onClick = { onSelect(if (selected == ch) null else ch) },
                label = { Text("$ch") },
            )
        }
    }
}
