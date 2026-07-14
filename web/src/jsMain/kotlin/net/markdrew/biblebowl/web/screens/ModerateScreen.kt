package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.w3c.dom.HTMLElement

/** Pending-question review queue; gated on QUESTION_MODERATE. */
object ModerateScreen {

    private lateinit var root: HTMLElement
    private lateinit var list: HTMLElement

    fun render(container: HTMLElement) {
        root = container
        root.child("h1", "page-title", "Pending review")
        list = root.child("div")
        reload()
    }

    private fun reload() {
        list.clear()
        list.spinner()
        Shell.scope.launch {
            try {
                val pending = Session.api.questions(status = QuestionStatus.PENDING)
                list.clear()
                if (pending.isEmpty()) {
                    list.child("p", "fs-5", "Queue is clear — nothing pending. 🎉")
                } else {
                    pending.forEach { q -> questionCard(q) }
                }
            } catch (e: Throwable) {
                list.clear()
                list.errorLine("Error: ${e.message}")
            }
        }
    }

    private fun questionCard(q: QuestionDto) {
        list.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("div", "d-flex justify-content-between align-items-center mb-2") {
                    child("span", "badge rounded-pill text-bg-light border", q.roundType.displayName)
                    q.chapter?.let {
                        child("span", "tbb-gold fw-semibold small", "${Session.season.eventScripture} $it")
                    }
                }
                child("p", "fs-5 mb-2", q.prompt)
                if (q.choices.isNotEmpty()) {
                    child("div", "mb-2") {
                        q.choices.forEachIndexed { i, choice -> child("div", "", "${'A' + i}. $choice") }
                    }
                }
                child("p", "fw-semibold mb-1", "Answer: ${q.answer}") {
                    setAttribute("style", "color:var(--tbb-navy);")
                }
                if (q.references.isNotEmpty()) {
                    child("p", "small mb-1", q.references.joinToString("; "))
                }
                q.authorName?.let { child("p", "text-muted small mb-2", "by $it") }
                child("div", "d-flex gap-2") {
                    child("button", "btn btn-primary flex-fill", "Approve") {
                        setAttribute("type", "button")
                        onClick { act(q, QuestionStatus.APPROVED) }
                    }
                    child("button", "btn btn-outline-primary flex-fill", "Reject") {
                        setAttribute("type", "button")
                        onClick { act(q, QuestionStatus.REJECTED) }
                    }
                }
            }
        }
    }

    private fun act(q: QuestionDto, status: QuestionStatus) {
        Shell.scope.launch {
            try {
                Session.api.moderate(q.id, status)
                reload()
            } catch (e: Throwable) {
                list.clear()
                list.errorLine("Error: ${e.message}")
            }
        }
    }
}
