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
import net.markdrew.biblebowl.api.ScopeSelection
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterChips(selected: ScopeSelection, onSelect: (ScopeSelection) -> Unit) {
    val set = LocalSeason.current.resolvedStudySet
    if (set.isSingleBook) {
        val book = set.books.single()
        ChipFlow {
            FilterChip(
                selected = selected.chapter == null,
                onClick = { onSelect(ScopeSelection()) },
                label = { Text("All") },
            )
            set.chapterRefs.forEach { ref ->
                val on = selected.chapter == ref.chapter
                FilterChip(
                    selected = on,
                    onClick = { onSelect(if (on) ScopeSelection() else ScopeSelection(book, ref.chapter)) },
                    label = { Text("${ref.chapter}") },
                )
            }
        }
        return
    }
    ChipFlow {
        FilterChip(
            selected = selected.book == null,
            onClick = { onSelect(ScopeSelection()) },
            label = { Text("All") },
        )
        set.books.forEach { book ->
            FilterChip(
                selected = selected.book == book,
                onClick = { onSelect(if (selected.book == book) ScopeSelection() else ScopeSelection(book)) },
                label = { Text(book.briefName) },
            )
        }
    }
    val book = selected.book ?: return
    ChipFlow {
        FilterChip(
            selected = selected.chapter == null,
            onClick = { onSelect(ScopeSelection(book)) },
            label = { Text("All of ${book.briefName}") },
        )
        set.chapterRefs.filter { it.book == book }.forEach { ref ->
            val on = selected.chapter == ref.chapter
            FilterChip(
                selected = on,
                onClick = { onSelect(ScopeSelection(book, if (on) null else ref.chapter)) },
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
