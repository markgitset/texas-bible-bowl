package net.markdrew.biblebowl.web.ui

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.lights
import net.markdrew.biblebowl.api.scopeLabel
import net.markdrew.biblebowl.api.tap
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent

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

/**
 * A wrapping single-select chip row over [options] (label → value). A non-null [keyGroup] makes the
 * row one keyboard tab stop (see [rovingGroup]).
 */
fun <T> Element.chipRow(
    options: List<Pair<String, T>>,
    selected: T,
    keyGroup: String? = null,
    onSelect: (T) -> Unit,
) {
    child("div", "d-flex flex-wrap gap-1 mb-2") {
        options.forEach { (label, value) ->
            chip(label, selected == value) { onSelect(value) }
        }
        keyGroup?.let { rovingGroup(it) }
    }
}

/**
 * Makes this row of chip buttons a single Tab stop (the WAI-ARIA "roving tabindex" pattern): Tab
 * lands on the selected chip (else the first), ←/→ (and ↑/↓, Home/End) move between chips, and
 * Space picks one — so a keyboard user crosses a 28-chapter row in one Tab instead of 28. Each chip
 * gets a stable `data-kbd-id` ("[name]-i") so a screen that re-renders on a pick can put focus
 * back where it was.
 */
fun HTMLElement.rovingGroup(name: String) {
    setAttribute("role", "group")
    val chips = querySelectorAll("button").asList().map { it as HTMLElement }
    if (chips.isEmpty()) return
    val home = chips.indexOfFirst { it.classList.contains("btn-primary") }.coerceAtLeast(0)
    chips.forEachIndexed { i, chip ->
        chip.setAttribute("data-kbd-id", "$name-$i")
        chip.tabIndex = if (i == home) 0 else -1
        chip.setAttribute("aria-pressed", chip.classList.contains("btn-primary").toString())
    }
    addEventListener("keydown", { e ->
        val key = (e as KeyboardEvent).key
        val at = chips.indexOf(e.target)
        if (at < 0) return@addEventListener
        val to = when (key) {
            "ArrowRight", "ArrowDown" -> (at + 1) % chips.size
            "ArrowLeft", "ArrowUp" -> (at - 1 + chips.size) % chips.size
            "Home" -> 0
            "End" -> chips.lastIndex
            else -> return@addEventListener
        }
        e.preventDefault()
        chips[at].tabIndex = -1
        chips[to].tabIndex = 0
        chips[to].focus()
    })
}

/**
 * A small keycap naming a keyboard shortcut. Hidden on touch-first devices (`.tbb-kbd` in
 * custom.css) unless [always] — e.g. answer letters, which double as labels.
 */
fun Element.kbd(key: String, always: Boolean = false, classes: String = "ms-2"): HTMLElement =
    child("kbd", "tbb-kbd${if (always) " tbb-kbd-always" else ""} $classes", key) {
        setAttribute("aria-hidden", "true")
    }

/**
 * The chapter filter, driven by the season's study set (wraps instead of scrolling, same rationale
 * as the Compose ChapterChips). Single-book sets render the familiar single row of chapter chips;
 * multi-book sets add a book row above it, with the chapter row showing only that book's in-set
 * chapters (partial sets have gaps — e.g. Life of Moses covers Exo 1-20 then 32-34).
 *
 * One chip row serves both modes. A plain picker toggles a single chapter; a [range] picker pairs
 * every tap with the one before it — first tap = through that chapter, later taps span from the
 * previous tap (the shared [tap] gesture). Every chapter the selection covers lights up, not just
 * the ends ([lights]) — with one row the lit span can't disagree with itself, which the old two-row
 * from/through layout did.
 */
fun Element.chapterChips(
    selected: ScopeSelection,
    range: Boolean = false,
    keyGroup: String? = null,
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
            keyGroup?.let { rovingGroup("$it-books") }
        }
        selected.book ?: return
    }
    val chapters = set.chapterRefs.filter { it.book == book }
    child("div", "d-flex flex-wrap gap-1 mb-2") {
        val allLabel = if (set.isSingleBook) "All" else "All of ${book.briefName}"
        chip(allLabel, selected.chapter == null && selected.fromChapter == null) {
            onSelect(if (set.isSingleBook) ScopeSelection() else ScopeSelection(book))
        }
        chapters.forEach { ref ->
            chip("${ref.chapter}", selected.lights(ref, set)) {
                onSelect(selected.tap(set, book, ref.chapter, range))
            }
        }
        keyGroup?.let { rovingGroup("$it-chapters") }
    }
    if (range) {
        child(
            "p", "form-text mt-0 mb-2",
            "Tap a chapter for everything through it, tap it again for just that chapter, " +
                "or tap a second chapter to span a range.",
        )
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
