package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.generation.quiz.headingChapterLabel
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Headings browser (docs/gui-redesign.md §5C) — the Round 5 material as a browsable list. Testing
 * yourself on it is Quiz Me's job (#quiz?source=headings), so there's no self-check mode here. Public.
 */
object HeadingsScreen {

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
                    list.errorLine(e)
                }
            }
        } else {
            renderList()
        }
    }

    private fun renderList() {
        list.clear()
        headings.orEmpty().forEach { heading -> list.headingCard(heading) }
    }

    private fun Element.headingCard(heading: HeadingDto) {
        child("div", "card section-card mb-2") {
            child("div", "card-body py-2") {
                child("div", "fw-semibold", heading.title)
                child("div", "d-flex justify-content-between align-items-center") {
                    val chapter = headingChapterLabel(heading.bookCode, heading.chapter, !Session.studySet.isSingleBook)
                    child("span", "tbb-gold small", "$chapter · ${heading.reference}")
                    child("span", "text-muted small", "${heading.index} of ${heading.total}")
                }
            }
        }
    }
}
