package net.markdrew.biblebowl.web.screens

import net.markdrew.biblebowl.api.schoolYear
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.child
import org.w3c.dom.HTMLElement

/**
 * Season/event hub (docs/gui-redesign.md §5F) — public season info now; registration, grading,
 * and My Scores dock here as role-aware cards in later phases.
 */
object EventScreen {

    fun render(container: HTMLElement) {
        val season = Session.season
        container.child("h1", "page-title", "${season.schoolYear} Season")
        val theme = season.eventTheme.takeIf { it.isNotBlank() && it != "TBD" }?.let { " Theme: $it." } ?: ""
        container.child("p", "fs-5", "This season's book is ${season.eventScripture} (ESV).$theme")
        container.child("p", "fw-semibold", "Event: ${season.eventDateRange}, ${season.eventYear}")

        container.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Competition rounds")
                child("table", "table table-sm mb-0") {
                    child("thead") {
                        child("tr") {
                            listOf("Round", "Name", "Questions", "Time", "Bible").forEach { child("th", text = it) }
                        }
                    }
                    child("tbody") {
                        Round.entries.forEach { round ->
                            child("tr") {
                                child("td", text = "R${round.number}")
                                child("td", text = round.displayName)
                                child("td", text = "${round.questions}")
                                child("td", text = "${round.minutes} min")
                                child("td", text = if (round.openBible) "Open" else "Closed")
                            }
                        }
                    }
                }
            }
        }

        container.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("h5", "card-title", "Dates, fees & registration")
                child("p", "mb-1", "Registration opens ${season.registrationOpens}; deadline: ${season.registrationDeadline}.")
                child("p", "mb-1", "Adults: ${season.priceAdult}")
                child("p", "mb-1", "Children (3–8): ${season.priceChild}")
                child("p", "mb-1", "Extra t-shirts: ${season.priceTshirt}")
                child("p", "text-muted mb-0") {
                    append("Locations and team registration are on ")
                    child("a", text = "texasbiblebowl.org") {
                        setAttribute("href", "https://texasbiblebowl.org")
                        setAttribute("target", "_blank")
                        setAttribute("rel", "noopener")
                    }
                    append(" for now. Registration moves into the app in an upcoming season.")
                }
            }
        }
    }
}
