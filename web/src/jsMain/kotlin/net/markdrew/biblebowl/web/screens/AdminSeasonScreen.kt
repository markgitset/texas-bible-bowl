package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement

/**
 * Season parameters editor (docs/gui-redesign.md §5G) for SEASON_MANAGE holders. Saves are live
 * immediately on both halves: the app re-reads the session season and the static site's params.js
 * patches its spans on the next page view. Entering a new event year starts the next season
 * (the prior year's record becomes history).
 *
 * The study material is picked from the standard 10-year rotation ([StandardStudySet]) — the
 * display name, book code, and chapter count all derive from the chosen set, which may span
 * several books (Joshua/Judges/Ruth) or partial chapters of multiple books (Life of Moses).
 */
object AdminSeasonScreen {

    private var draft: SeasonDto = Session.season
    private var message: String? = null
    private var isError = false

    fun render(container: HTMLElement) {
        draft = Session.season
        container.child("h1", "page-title", "Season settings")
        container.child(
            "p", "text-muted",
            "These values drive the public site and the app (dates, fees, the study material). " +
                "Saving is live immediately. A new event year starts the next season.",
        )

        val form = container.child("form")
        form.field("Event year (e.g. 2027)", draft.eventYear) { draft = draft.copy(eventYear = it) }
        form.field("Event dates (e.g. April 2–4)", draft.eventDateRange) { draft = draft.copy(eventDateRange = it) }
        form.field("Theme (TBD hides it)", draft.eventTheme) { draft = draft.copy(eventTheme = it) }
        form.studySetSelect()
        form.field("Registration opens", draft.registrationOpens) { draft = draft.copy(registrationOpens = it) }
        form.field("Registration deadline", draft.registrationDeadline) { draft = draft.copy(registrationDeadline = it) }
        form.field("Scholarship deadline", draft.scholarshipDeadline) { draft = draft.copy(scholarshipDeadline = it) }
        form.field("Price — adult", draft.priceAdult) { draft = draft.copy(priceAdult = it) }
        form.field("Price — child (ages 3–8)", draft.priceChild) { draft = draft.copy(priceChild = it) }
        form.field("Price — extra t-shirt", draft.priceTshirt) { draft = draft.copy(priceTshirt = it) }
        form.field("Prior-year scholarship total", draft.scholarshipAmount) { draft = draft.copy(scholarshipAmount = it) }
        form.field("TBB scholarship", draft.tbbScholarshipAmount) { draft = draft.copy(tbbScholarshipAmount = it) }
        form.field("Mary Orbison scholarship", draft.maryOrbisonAmount) { draft = draft.copy(maryOrbisonAmount = it) }
        form.field("Paul Hendrickson scholarship", draft.paulHendricksonAmount) {
            draft = draft.copy(paulHendricksonAmount = it)
        }

        val save = form.child("button", "btn btn-primary w-100 mt-2", "Save season") {
            setAttribute("type", "submit")
        } as HTMLButtonElement
        val messageSlot = form.child("div")
        message?.let {
            messageSlot.child("p", (if (isError) "text-danger" else "tbb-gold fw-semibold") + " mt-3 mb-0", it)
        }

        form.addEventListener("submit", { event ->
            event.preventDefault()
            if (draft.eventYear.isBlank()) return@addEventListener
            save.disabled = true
            message = null
            messageSlot.clear()
            Shell.scope.launch {
                try {
                    val saved = Session.api.updateSeason(draft)
                    isError = false
                    message = "Saved — live on the site and app."
                    Session.seasonSaved(saved) // re-renders every screen, including this one + message
                } catch (e: Throwable) {
                    isError = true
                    message = "Save failed: ${e.message}"
                    save.disabled = false
                    messageSlot.clear()
                    messageSlot.child("p", "text-danger mt-3 mb-0", message!!)
                }
            }
        })
    }

    private fun Element.field(label: String, value: String, onChange: (String) -> Unit) {
        child("div", "mb-3") {
            child("label", "form-label", label)
            val input = child("input", "form-control") as HTMLInputElement
            input.value = value
            input.addEventListener("input", { onChange(input.value) })
        }
    }

    /** Dropdown over the standard 10-year rotation; shows each set's name and chapter count. */
    private fun Element.studySetSelect() {
        child("div", "mb-3") {
            child("label", "form-label", "Study material")
            val select = child("select", "form-select") as HTMLSelectElement
            StandardStudySet.entries.forEach { standard ->
                val option = select.child("option", text = "${standard.set.name} (${standard.set.chapterCount} chapters)")
                    as HTMLOptionElement
                option.value = standard.set.simpleName
                if (standard.set.simpleName == (StandardStudySet.parseOrNull(draft.studySet) ?: StandardStudySet.DEFAULT).simpleName) {
                    option.selected = true
                }
            }
            select.addEventListener("change", {
                val set = StandardStudySet.parse(select.value)
                draft = draft.copy(
                    studySet = set.simpleName,
                    eventScripture = set.name,
                    bookCode = set.chapterRanges.first().start.book.name,
                    chapterCount = set.chapterCount,
                )
            })
        }
    }
}
