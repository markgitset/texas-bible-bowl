package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ParticipationDto
import net.markdrew.biblebowl.api.PersonWithParticipationsDto
import net.markdrew.biblebowl.api.division
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Registrar tool: find and merge duplicate people. Global person matching (one `people` row per
 * human across seasons) makes duplicates likelier — two spellings of the same child enrolled in
 * different seasons — and FK-everywhere makes the fix clean: the survivor absorbs the other's
 * participations, scores, tester ids, and claim, and the loser is deleted. Route-gated on
 * event-wide REGISTRATION_MANAGE and re-checked server-side; refused when the two share a season.
 */
object AdminMergePeopleScreen {

    private var results: List<PersonWithParticipationsDto> = emptyList()
    private var searched = false
    private var searchSeq = 0 // typeahead guard: out-of-order responses are dropped
    private var keepId: String? = null
    private var mergeId: String? = null
    // Outcome of the last merge, surfaced under the action button; survives the actionSlot rebuild
    // that renderResults() does, and clears on the next search or selection change.
    private var status: Pair<String, Boolean>? = null // (message, isError)
    private lateinit var resultsSlot: HTMLElement
    private lateinit var actionSlot: HTMLElement

    fun render(container: HTMLElement) {
        results = emptyList()
        searched = false
        keepId = null
        mergeId = null
        status = null
        searchSeq++

        container.child("h1", "page-title", "Merge duplicate people")
        container.child(
            "p", "text-muted",
            "Search for a person by name, then choose which record to keep and which to merge into it. " +
                "The merged record's registrations, scores, and claim move to the kept person, and the " +
                "duplicate is deleted. People registered in the same season can't be merged — fix the " +
                "duplicate registration first.",
        )

        val search = container.child("input", "form-control mb-3") as HTMLInputElement
        search.setAttribute("placeholder", "Search people by name…")
        resultsSlot = container.child("div")
        actionSlot = container.child("div", "mt-3")
        search.addEventListener("input", {
            val query = search.value.trim()
            val seq = ++searchSeq
            status = null
            if (query.length < 2) {
                results = emptyList()
                searched = false
                keepId = null
                mergeId = null
                renderResults()
                return@addEventListener
            }
            Shell.scope.launch {
                runCatching { Session.api.searchPeople(query) }.onSuccess { found ->
                    if (seq != searchSeq) return@onSuccess // a newer search superseded this one
                    results = found
                    searched = true
                    // Drop selections no longer in view.
                    if (results.none { it.person.id == keepId }) keepId = null
                    if (results.none { it.person.id == mergeId }) mergeId = null
                    renderResults()
                }
            }
        })
    }

    private fun renderResults() {
        resultsSlot.clear()
        if (results.isEmpty()) {
            if (searched) resultsSlot.child("p", "text-muted", "No matching people.")
            renderAction()
            return
        }
        resultsSlot.child("div", "row fw-semibold small text-muted mb-1") {
            child("div", "col-1", "Keep")
            child("div", "col-1", "Merge")
            child("div", "col", "Person")
        }
        results.forEach { renderPersonRow(it) }
        renderAction()
    }

    private fun renderPersonRow(pwp: PersonWithParticipationsDto) {
        val person = pwp.person
        resultsSlot.child("div", "row align-items-center border-top py-2") {
            // Keep / Merge radios — one of each across all rows; a row can't be both.
            child("div", "col-1") {
                radio(this, "merge-keep", person.id == keepId) {
                    keepId = person.id
                    if (mergeId == person.id) mergeId = null
                    status = null
                    renderResults()
                }
            }
            child("div", "col-1") {
                radio(this, "merge-merge", person.id == mergeId) {
                    mergeId = person.id
                    if (keepId == person.id) keepId = null
                    status = null
                    renderResults()
                }
            }
            child("div", "col") {
                child("span", "fw-semibold", person.name)
                person.division(Session.season)?.let { child("span", "text-muted small ms-2", it.displayName) }
                person.birthdate?.let { child("span", "text-muted small ms-2", it) }
                if (pwp.participations.isEmpty()) {
                    child("div", "text-muted small", "No registrations.")
                } else {
                    pwp.participations.forEach { child("div", "small", participationLine(it)) }
                }
            }
        }
    }

    private fun radio(parent: Element, group: String, checked: Boolean, onSelect: () -> Unit) {
        val input = parent.child("input", "form-check-input") as HTMLInputElement
        input.type = "radio"
        input.name = group
        input.checked = checked
        input.addEventListener("change", { if (input.checked) onSelect() })
    }

    private fun renderAction() {
        actionSlot.clear()
        val keep = results.firstOrNull { it.person.id == keepId }
        val merge = results.firstOrNull { it.person.id == mergeId }
        val ready = keep != null && merge != null && keep.person.id != merge.person.id
        if (keep != null && merge != null) {
            actionSlot.child(
                "p", "mb-2",
                "Merge \"${merge.person.name}\" into \"${keep.person.name}\" — " +
                    "${merge.person.name}'s registrations and scores move to ${keep.person.name}, " +
                    "then ${merge.person.name} is deleted.",
            )
        }
        val button = actionSlot.child("button", "btn btn-danger", "Merge") {
            setAttribute("type", "button")
        } as HTMLButtonElement
        button.disabled = !ready
        val messageSlot = actionSlot.child("div", "mt-2")
        status?.let { (message, isError) ->
            if (isError) messageSlot.errorLine(message)
            else messageSlot.child("p", "tbb-gold fw-semibold mb-0", message)
        }
        button.onClick {
            if (keep == null || merge == null) return@onClick
            button.disabled = true
            status = null
            Shell.scope.launch {
                try {
                    val result = Session.api.mergePeople(keep.person.id, merge.person.id)
                    keepId = null
                    mergeId = null
                    results = results.filter { it.person.id != merge.person.id }
                        .map { if (it.person.id == result.person.person.id) result.person else it }
                    val seasons = result.person.participations.joinToString(", ") { it.seasonYear }
                    status = "Merged into ${result.person.person.name} — now registered in $seasons." to false
                    renderResults()
                } catch (e: Throwable) {
                    status = (e.message ?: "Merge failed") to true
                    renderResults()
                }
            }
        }
    }

    /** "2027 · First Baptist — Contestant · Team Lions · Tester #12" for one participation. */
    private fun participationLine(p: ParticipationDto): String {
        val roles = buildList {
            if (p.isContestant) add("Contestant")
            if (p.isCoach) add("Coach")
            addAll(p.positions)
        }.joinToString(", ").ifEmpty { "Attendee" }
        return buildString {
            append("${p.seasonYear} · ${p.congregationName} — $roles")
            p.teamName?.let { append(" · Team $it") }
            p.testerId?.let { append(" · Tester #$it") }
        }
    }
}
