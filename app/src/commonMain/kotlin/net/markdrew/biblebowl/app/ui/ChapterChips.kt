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

/**
 * The chapter filter as a wrapping chip flow, driven by the season's study set. Wrapping (not a
 * horizontally scrolling row) on purpose: the chips never fit one line, and horizontal scroll is
 * effectively unusable with a mouse on web/desktop.
 *
 * Selection is canonical (book + book-relative chapter — see [ScopeSelection]), which is what keeps
 * question filters and generated-material requests valid across the 10-year study rotation.
 * Single-book sets render the familiar "All + chapters" row; multi-book sets add a book row above
 * it, with the chapter row showing only that book's in-set chapters (partial sets have gaps —
 * e.g. Life of Moses covers Exo 1-20 then 32-34). Clicking the selected chip clears it.
 *
 * Every chapter the selection covers is selected, not just the end the user last tapped — on a
 * [cumulative] picker that's the reach-back to the set's first chapter, and on a [range] picker it's
 * everything between the two rows. Only the endpoint chip toggles off; tapping a lower selected chip
 * moves the endpoint back to it.
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

    // The start row exists only on range pickers; elsewhere a scope still has just the one end, and a
    // second row of 28 chips would be noise on every sheet that can't use it.
    if (range) {
        Text("From chapter", style = MaterialTheme.typography.labelLarge)
        ChipFlow {
            FilterChip(
                selected = selected.fromChapter == null,
                onClick = { onSelect(selected.copy(fromChapter = null)) },
                label = { Text("Start") },
            )
            chapters.forEach { ref ->
                val on = selected.fromChapter == ref.chapter
                FilterChip(
                    selected = on,
                    onClick = { onSelect(selected.copy(book = book, fromChapter = if (on) null else ref.chapter)) },
                    label = { Text("${ref.chapter}") },
                )
            }
        }
        Text("Through chapter", style = MaterialTheme.typography.labelLarge)
    }
    ChipFlow {
        val allLabel = if (set.isSingleBook) "All" else "All of ${book.briefName}"
        FilterChip(
            selected = selected.chapter == null,
            onClick = {
                onSelect(
                    when {
                        range -> selected.copy(chapter = null)
                        set.isSingleBook -> ScopeSelection()
                        else -> ScopeSelection(book)
                    },
                )
            },
            label = { Text(if (range) "End" else allLabel) },
        )
        chapters.forEach { ref ->
            val end = selected.chapter == ref.chapter
            FilterChip(
                selected = selected.lights(ref, cumulative, set),
                onClick = { onSelect(selected.copy(book = book, chapter = if (end) null else ref.chapter)) },
                label = { Text("${ref.chapter}") },
            )
        }
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
