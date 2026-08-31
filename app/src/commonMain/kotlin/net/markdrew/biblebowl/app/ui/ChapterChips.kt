package net.markdrew.biblebowl.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.lights
import net.markdrew.biblebowl.api.resolvedStudySet
import net.markdrew.biblebowl.api.tap

/**
 * The chapter filter as a wrapping chip flow, driven by the season's study set. Wrapping (not a
 * horizontally scrolling row) on purpose: the chips never fit one line, and horizontal scroll is
 * effectively unusable with a mouse on web/desktop.
 *
 * Selection is canonical (book + book-relative chapter — see [ScopeSelection]), which is what keeps
 * question filters and generated-material requests valid across the 10-year study rotation.
 * Single-book sets render the familiar "All + chapters" row; multi-book sets add a book row above
 * it, with the chapter row showing only that book's in-set chapters (partial sets have gaps —
 * e.g. Life of Moses covers Exo 1-20 then 32-34).
 *
 * One chip row serves every mode. A plain picker toggles a single chapter; a [range] picker spans
 * two taps and a third tap starts over (the shared [tap] gesture); a [cumulative] endpoint reaches
 * back to the set's first chapter. Every chapter the selection covers lights up, not just the ends
 * ([lights]) — with one row the lit span can't disagree with itself, which the old two-row
 * from/through layout did.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterChips(
    selected: ScopeSelection,
    cumulative: Boolean = false,
    range: Boolean = false,
    onSelect: (ScopeSelection) -> Unit,
) {
    val set = LocalSeason.current.resolvedStudySet
    val book = if (set.isSingleBook) set.books.single() else {
        ChipFlow {
            FilterChip(
                selected = selected.book == null,
                onClick = { onSelect(ScopeSelection()) },
                label = { Text("All") },
            )
            set.books.forEach { b ->
                FilterChip(
                    selected = selected.book == b,
                    onClick = { onSelect(if (selected.book == b) ScopeSelection() else ScopeSelection(b)) },
                    label = { Text(b.briefName) },
                )
            }
        }
        selected.book ?: return
    }
    val chapters = set.chapterRefs.filter { it.book == book }
    ChipFlow {
        val allLabel = if (set.isSingleBook) "All" else "All of ${book.briefName}"
        FilterChip(
            selected = selected.chapter == null && selected.fromChapter == null,
            onClick = { onSelect(if (set.isSingleBook) ScopeSelection() else ScopeSelection(book)) },
            label = { Text(allLabel) },
        )
        chapters.forEach { ref ->
            FilterChip(
                selected = selected.lights(ref, cumulative, set),
                onClick = { onSelect(selected.tap(book, ref.chapter, range, cumulative)) },
                label = { Text("${ref.chapter}") },
            )
        }
    }
    if (range) {
        Text(
            if (cumulative) "Tap a chapter for everything through it, tap it again for just that chapter, " +
                "or tap a second chapter to span a range."
            else "Tap a chapter, then a second one to span a range.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        content()
    }
}
