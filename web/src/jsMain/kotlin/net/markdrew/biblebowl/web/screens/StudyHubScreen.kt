package net.markdrew.biblebowl.web.screens

import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.child
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Study hub — the app's default landing (docs/gui-redesign.md §5C): compact cards, one tap to
 * everything, zero auth. Reading the text links out to dedicated ESV readers rather than an
 * in-app view — better reading tools, and no ESV text on the client.
 */
object StudyHubScreen {

    fun render(container: HTMLElement) {
        val season = Session.season
        container.child("h1", "page-title", "This season: ${season.eventScripture}")
        container.child("p", "fw-semibold tbb-gold", "${season.schoolYear} · all ${season.chapterCount} chapters (ESV)")

        readOnlineCard(container)
        hubCard(
            container,
            title = "Names & numbers indices",
            subtitle = "Every proper name and number in ${season.eventScripture}, with all its verses. Search or browse.",
            route = Routes.STUDY_INDICES,
        )
        hubCard(
            container,
            title = "Chapter headings",
            subtitle = "Browse every ESV section heading (the Round 5 material) or flip to self-check mode.",
            route = Routes.STUDY_HEADINGS,
        )
        hubCard(
            container,
            title = "Quiz yourself",
            subtitle = "Drill the community question bank or chapter headings, with instant feedback.",
            route = Routes.QUIZ,
        )
        hubCard(
            container,
            title = "Download study PDFs",
            subtitle = "The highlighted study text, flashcards, indices, and practice tests.",
            route = Routes.DOWNLOADS,
        )
    }

    private fun readOnlineCard(container: Element) {
        val season = Session.season
        // Good ESV readers to link out to — the app deliberately hosts no reading view (per Mark).
        val readingLinks = listOf(
            "ESV.org" to "https://www.esv.org/${season.eventScripture}+1/",
            "YouVersion" to "https://www.bible.com/bible/59/${season.bookCode}.1.ESV",
            "BibleGateway" to "https://www.biblegateway.com/passage/?search=${season.eventScripture}+1&version=ESV",
        )
        container.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Read ${season.eventScripture} online")
                child("p", "card-text text-muted mb-2", "Read the ESV text in a dedicated Bible app or site:")
                child("div", "d-flex flex-wrap gap-2") {
                    readingLinks.forEach { (name, url) ->
                        child("a", "btn btn-outline-primary btn-sm", name) {
                            setAttribute("href", url)
                            setAttribute("target", "_blank")
                            setAttribute("rel", "noopener")
                        }
                    }
                }
            }
        }
    }

    private fun hubCard(container: Element, title: String, subtitle: String, route: String) {
        container.child("a", "card section-card mb-3 text-decoration-none") {
            setAttribute("href", "#$route")
            child("div", "card-body") {
                child("h5", "card-title", title)
                child("p", "card-text text-muted mb-0", subtitle)
            }
        }
    }
}
