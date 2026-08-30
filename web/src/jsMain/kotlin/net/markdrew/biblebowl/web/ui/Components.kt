package net.markdrew.biblebowl.web.ui

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.lights
import net.markdrew.biblebowl.api.scopeLabel
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement

private var nextControlId = 0

/** A single filter chip (pill button): navy when selected, outlined otherwise. */
fun Element.chip(label: String, selected: Boolean, onSelect: () -> Unit) {
    child(
        "button",
        "btn btn-sm rounded-pill " + (if (selected) "btn-primary" else "btn-outline-primary"),
        label,
    ) {
        setAttribute("type", "button")
        onClick(onSelect)
    }
}

/** A wrapping single-select chip row over [options] (label → value). */
fun <T> Element.chipRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    child("div", "d-flex flex-wrap gap-1 mb-2") {
        options.forEach { (label, value) ->
            chip(label, selected == value) { onSelect(value) }
        }
    }
}

/**
 * The chapter filter, driven by the season's study set (wraps instead of scrolling, same rationale
 * as the Compose ChapterChips). Single-book sets render the familiar single row of chapter chips;
 * multi-book sets add a book row above it, with the chapter row showing only that book's in-set
 * chapters (partial sets have gaps — e.g. Life of Moses covers Exo 1-20 then 32-34). Clicking the
 * selected chip clears it.
 *
 * Every chapter the selection covers lights up, not just the end the user last clicked — on a
 * [cumulative] picker that's the reach-back to the set's first chapter, and on a range it's everything
 * between the two rows. Only the endpoint chip toggles off; clicking a lower lit chip moves the
 * endpoint back to it.
 */
fun Element.chapterChips(
    selected: ScopeSelection,
    cumulative: Boolean = false,
    range: Boolean = false,
    onSelect: (ScopeSelection) -> Unit,
) {
    val set = Session.studySet
    val book = if (set.isSingleBook) set.books.single() else {
        child("div", "d-flex flex-wrap gap-1 mb-2") {
            chip("All", selected.book == null) { onSelect(ScopeSelection()) }
            set.books.forEach { b ->
                chip(b.briefName, selected.book == b) {
                    onSelect(if (selected.book == b) ScopeSelection() else ScopeSelection(b))
                }
            }
        }
        selected.book ?: return
    }
    val chapters = set.chapterRefs.filter { it.book == book }

    // The start row exists only on range pickers; elsewhere a scope still has just the one end, and a
    // second row of 28 chips would be noise on every card that can't use it.
    if (range) {
        child("p", "fw-semibold mb-1", "From chapter")
        child("div", "d-flex flex-wrap gap-1 mb-2") {
            chip("Start", selected.fromChapter == null) { onSelect(selected.copy(fromChapter = null)) }
            chapters.forEach { ref ->
                val on = selected.fromChapter == ref.chapter
                chip("${ref.chapter}", on) {
                    onSelect(selected.copy(book = book, fromChapter = if (on) null else ref.chapter))
                }
            }
        }
        child("p", "fw-semibold mb-1", "Through chapter")
    }
    child("div", "d-flex flex-wrap gap-1 mb-3") {
        val allLabel = if (set.isSingleBook) "All" else "All of ${book.briefName}"
        chip(if (range) "End" else allLabel, selected.chapter == null) {
            onSelect(
                when {
                    range -> selected.copy(chapter = null)
                    set.isSingleBook -> ScopeSelection()
                    else -> ScopeSelection(book)
                },
            )
        }
        chapters.forEach { ref ->
            val end = selected.chapter == ref.chapter
            chip("${ref.chapter}", selected.lights(ref, cumulative, set)) {
                onSelect(selected.copy(book = book, chapter = if (end) null else ref.chapter))
            }
        }
    }
}

/** A question's scripture badge (see [scopeLabel]); the season label covers legacy rows. */
fun questionScopeLabel(q: QuestionDto): String? = q.scopeLabel(Session.season.eventScripture)

/** A Bootstrap switch row, label on the left. */
fun Element.optionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val id = "tbb-switch-${nextControlId++}"
    child("div", "form-check form-switch mb-2") {
        val input = child("input", "form-check-input") as HTMLInputElement
        input.type = "checkbox"
        input.id = id
        input.checked = checked
        input.addEventListener("change", { onChange(input.checked) })
        child("label", "form-check-label", label) { setAttribute("for", id) }
    }
}
