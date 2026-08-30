package net.markdrew.biblebowl.web.ui

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.ScopeSelection
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
 * On a [cumulative] picker ("through chapter"), every chapter up to the chosen one lights up too, so
 * the chips show the range the download will actually cover rather than just its endpoint. Only the
 * endpoint chip toggles off; clicking a lower lit chip moves the endpoint back to it.
 */
fun Element.chapterChips(
    selected: ScopeSelection,
    cumulative: Boolean = false,
    onSelect: (ScopeSelection) -> Unit,
) {
    val set = Session.studySet
    val endRef = selected.chapterRef
    if (set.isSingleBook) {
        val book = set.books.single()
        child("div", "d-flex flex-wrap gap-1 mb-3") {
            chip("All", selected.chapter == null) { onSelect(ScopeSelection()) }
            set.chapterRefs.forEach { ref ->
                val end = selected.chapter == ref.chapter
                chip("${ref.chapter}", end || (cumulative && endRef != null && ref <= endRef)) {
                    onSelect(if (end) ScopeSelection() else ScopeSelection(book, ref.chapter))
                }
            }
        }
        return
    }
    child("div", "d-flex flex-wrap gap-1 mb-2") {
        chip("All", selected.book == null) { onSelect(ScopeSelection()) }
        set.books.forEach { book ->
            chip(book.briefName, selected.book == book) {
                onSelect(if (selected.book == book) ScopeSelection() else ScopeSelection(book))
            }
        }
    }
    val book = selected.book ?: return
    child("div", "d-flex flex-wrap gap-1 mb-3") {
        chip("All of ${book.briefName}", selected.chapter == null) { onSelect(ScopeSelection(book)) }
        set.chapterRefs.filter { it.book == book }.forEach { ref ->
            val end = selected.chapter == ref.chapter
            chip("${ref.chapter}", end || (cumulative && endRef != null && ref <= endRef)) {
                onSelect(ScopeSelection(book, if (end) null else ref.chapter))
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
