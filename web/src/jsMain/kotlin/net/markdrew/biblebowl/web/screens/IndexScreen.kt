package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.spinner
import net.markdrew.biblebowl.web.ui.chip
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/** The two study indices, each generated from the ESV text (word lists + curated overrides). */
private enum class IndexKind(val label: String, val pdfPath: String) {
    NUMBERS("Numbers", "/generate/numbers-index.pdf"),
    NAMES("Names", "/generate/names-index.pdf"),
}

/**
 * Study indices: every number or proper name in the season book with the verses it occurs in. A
 * segmented toggle switches between the two; each has a live text filter and a PDF export link.
 */
object IndexScreen {

    private var kind = IndexKind.NUMBERS // sticky across visits
    private var filter = ""

    private lateinit var root: HTMLElement
    private lateinit var results: HTMLElement
    private var entries: List<IndexEntryDto>? = null

    fun render(container: HTMLElement) {
        root = container
        filter = ""
        root.child("h1", "page-title", "Names & numbers indices")

        root.child("div", "d-flex flex-wrap gap-1 mb-3") {
            IndexKind.entries.forEach { k ->
                chip(k.label, kind == k) {
                    if (kind != k) {
                        kind = k
                        root.clear()
                        render(root)
                    }
                }
            }
        }

        root.child("div", "d-flex gap-2 mb-3") {
            val input = child("input", "form-control") as HTMLInputElement
            input.type = "search"
            input.placeholder = "Filter ${kind.label.lowercase()}"
            input.addEventListener("input", {
                filter = input.value
                renderResults()
            })
            child("a", "btn btn-outline-primary", "PDF") {
                setAttribute("href", Session.api.baseUrl + kind.pdfPath)
                setAttribute("target", "_blank")
                setAttribute("rel", "noopener")
            }
        }

        results = root.child("div")
        load()
    }

    private fun load() {
        entries = null
        results.clear()
        results.spinner()
        Shell.scope.launch {
            try {
                entries = when (kind) {
                    IndexKind.NUMBERS -> Session.api.numbersIndex()
                    IndexKind.NAMES -> Session.api.namesIndex()
                }
                renderResults()
            } catch (e: Throwable) {
                results.clear()
                results.errorLine("Couldn't load the ${kind.label.lowercase()} index: ${e.message}")
            }
        }
    }

    // Filter changes update only the results list, leaving the input (and its focus) alone.
    private fun renderResults() {
        val list = entries ?: return
        results.clear()
        val shown = list.filter { filter.isBlank() || it.key.contains(filter.trim(), ignoreCase = true) }
        results.child("p", "text-muted small mb-2", "${shown.size} of ${list.size} ${kind.label.lowercase()}")
        shown.forEach { entry ->
            results.child("div", "card section-card mb-2") {
                child("div", "card-body py-2") {
                    child("div", "d-flex justify-content-between") {
                        child("span", "fw-bold", entry.key)
                        child("span", "tbb-gold fw-semibold", "×${entry.total}")
                    }
                    child(
                        "div", "text-muted small",
                        entry.references.joinToString(", ") { r ->
                            r.reference + (if (r.count > 1) " (×${r.count})" else "")
                        },
                    )
                }
            }
        }
    }
}
