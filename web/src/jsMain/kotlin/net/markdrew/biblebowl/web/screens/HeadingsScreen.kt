package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import net.markdrew.biblebowl.web.ui.chip
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Headings browser (docs/gui-redesign.md §5C) — the Round 5 material as a browsable list, with a
 * flip-to-test self-check mode: the interactive twin of the heading-flashcards PDF. Public.
 */
object HeadingsScreen {

    private var selfCheck = false

    private lateinit var root: HTMLElement
    private lateinit var list: HTMLElement
    private var headings: List<HeadingDto>? = null

    fun render(container: HTMLElement) {
        root = container
        root.child("h1", "page-title", "Chapter headings")
        root.child(
            "p", "text-muted",
            "Every ESV section heading in ${Session.season.eventScripture} — the Round 5 material.",
        )
        root.child("div", "d-flex flex-wrap gap-1 mb-3") {
            chip("Browse", !selfCheck) { setMode(false) }
            chip("Self-check", selfCheck) { setMode(true) }
        }
        list = root.child("div")

        val cached = headings
        if (cached == null) {
            list.spinner()
            Shell.scope.launch {
                try {
                    headings = Session.api.headings()
                    renderList()
                } catch (e: Throwable) {
                    list.clear()
                    list.errorLine("Couldn't load headings: ${e.message}")
                }
            }
        } else {
            renderList()
        }
    }

    private fun setMode(check: Boolean) {
        if (selfCheck == check) return
        selfCheck = check
        root.clear()
        render(root) // mode flip re-renders, which also resets every card's reveal
    }

    private fun renderList() {
        list.clear()
        headings.orEmpty().forEach { heading -> list.headingCard(heading) }
    }

    private fun Element.headingCard(heading: HeadingDto) {
        var revealed = false
        child("div", "card section-card mb-2" + if (selfCheck) " tbb-clickable" else "") {
            val body = child("div", "card-body py-2") {
                child("div", "fw-semibold", heading.title)
            }
            val line = body.child("div", "d-flex justify-content-between align-items-center")
            fun renderLine() {
                line.clear()
                if (!selfCheck || revealed) {
                    line.child(
                        "span",
                        if (selfCheck) "tbb-gold fw-semibold small" else "tbb-gold small",
                        "Chapter ${heading.chapter} · ${heading.reference}",
                    )
                } else {
                    line.child("span", "text-muted fst-italic small", "Tap to reveal chapter")
                }
                line.child("span", "text-muted small", "${heading.index} of ${heading.total}")
            }
            renderLine()
            if (selfCheck) onClick { revealed = !revealed; renderLine() }
        }
    }
}
