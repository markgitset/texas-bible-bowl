package net.markdrew.biblebowl.web.ui

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
 * The chapter filter: an "All" chip plus one per chapter of the season book (wraps instead of
 * scrolling, same rationale as the Compose ChapterChips). Clicking the selected chip clears it.
 */
fun Element.chapterChips(selected: Int?, onSelect: (Int?) -> Unit) {
    child("div", "d-flex flex-wrap gap-1 mb-3") {
        chip("All", selected == null) { onSelect(null) }
        (1..Session.season.chapterCount).forEach { ch ->
            chip("$ch", selected == ch) { onSelect(if (selected == ch) null else ch) }
        }
    }
}

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
