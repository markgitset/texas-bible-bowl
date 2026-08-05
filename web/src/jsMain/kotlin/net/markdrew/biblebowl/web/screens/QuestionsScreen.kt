package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.web.Routes
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.scopeQueryParams
import net.markdrew.biblebowl.model.bookByCode
import net.markdrew.biblebowl.web.ui.chapterChips
import net.markdrew.biblebowl.web.ui.questionScopeLabel
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Community question bank — browse is public (docs/gui-redesign.md §5D). Voting requires sign-in:
 * an anonymous vote routes to contextual sign-in. Submit/moderate affordances render only for
 * permission holders (§7.8 — never disabled-but-visible).
 */
object QuestionsScreen {

    private var selection = ScopeSelection() // sticky across visits

    private lateinit var root: HTMLElement
    private lateinit var list: HTMLElement

    fun render(container: HTMLElement, params: Map<String, String> = emptyMap()) {
        // A scoped deep link (#questions?book=ACT&chapter=22) seeds the filter; chip changes write
        // the canonical form back to the hash, so the view stays shareable across seasons.
        if (params.isNotEmpty()) {
            selection = ScopeSelection(
                book = params[StudyScopeParams.BOOK]?.let { bookByCode(it) },
                chapter = params[StudyScopeParams.CHAPTER]?.toIntOrNull(),
            )
        }
        root = container
        root.child("h1", "page-title", "Questions")

        val user = Session.user
        val canSubmit = user != null && Permission.QUESTION_SUBMIT in user.permissions
        val canModerate = user != null && Permission.QUESTION_MODERATE in user.permissions
        if (canSubmit || canModerate) {
            root.child("div", "d-flex gap-2 mb-3") {
                if (canSubmit) {
                    child("a", "btn btn-primary btn-sm", "New question") {
                        setAttribute("href", "#${Routes.QUESTIONS_NEW}")
                    }
                }
                if (canModerate) {
                    child("a", "btn btn-outline-primary btn-sm", "Moderate") {
                        setAttribute("href", "#${Routes.QUESTIONS_MODERATE}")
                    }
                }
            }
        }

        root.chapterChips(selection) {
            selection = it
            Shell.replaceParams(scopeQueryParams(Session.studySet, it))
            root.clear()
            render(root)
        }

        list = root.child("div")
        reload()
    }

    private fun scopeLabel(): String? = selection.book?.let { book ->
        selection.chapter?.let { "${book.briefName} $it" } ?: book.briefName
    }

    private fun reload() {
        list.clear()
        list.spinner()
        Shell.scope.launch {
            try {
                val questions = Session.api.questions(
                    chapter = selection.chapter,
                    book = selection.book?.name,
                )
                list.clear()
                if (questions.isEmpty()) {
                    val scope = scopeLabel()?.let { " for $it" } ?: ""
                    list.child("p", "fs-5", "No approved questions yet$scope.")
                } else {
                    questions.forEach { q -> list.questionCard(q) }
                }
            } catch (e: Throwable) {
                list.clear()
                list.errorLine("Error: ${e.message}")
            }
        }
    }

    private fun Element.questionCard(q: QuestionDto) {
        var revealed = false
        child("div", "card section-card mb-3 tbb-clickable") {
            val body = child("div", "card-body")
            body.child("div", "d-flex justify-content-between align-items-center mb-2") {
                child("span", "badge rounded-pill text-bg-light border", q.roundType.displayName)
                questionScopeLabel(q)?.let { child("span", "tbb-gold fw-semibold small", it) }
            }
            body.child("p", "fs-5 mb-2", q.prompt)
            if (q.choices.isNotEmpty()) {
                body.child("div", "mb-2") {
                    q.choices.forEachIndexed { i, choice ->
                        child("div", "", "${'A' + i}. $choice")
                    }
                }
            }

            val answerSlot = body.child("div", "mb-2")
            fun renderAnswer() {
                answerSlot.clear()
                if (revealed) {
                    answerSlot.child("p", "fw-semibold fs-5 mb-1", q.answer) {
                        setAttribute("style", "color:var(--tbb-navy);")
                    }
                    if (q.references.isNotEmpty()) {
                        answerSlot.child("p", "small mb-0", q.references.joinToString("; "))
                    }
                } else {
                    answerSlot.child("p", "text-muted fst-italic small mb-0", "Tap to reveal answer")
                }
            }
            renderAnswer()
            onClick { revealed = !revealed; renderAnswer() }

            body.child("div", "d-flex justify-content-between align-items-center") {
                child("span", "text-muted small", q.authorName?.let { "by $it" } ?: "")
                child("button", "btn btn-link btn-sm", "▲ ${q.votes}") {
                    setAttribute("type", "button")
                    addEventListener("click", { event ->
                        event.preventDefault()
                        event.stopPropagation() // don't also toggle the card's reveal
                        vote(q)
                    })
                }
            }
        }
    }

    private fun vote(q: QuestionDto) {
        if (Session.user == null) {
            Shell.navigate(Routes.SIGN_IN)
            return
        }
        Shell.scope.launch {
            try {
                Session.api.vote(q.id)
                reload()
            } catch (e: Throwable) {
                list.clear()
                list.errorLine("Error: ${e.message}")
            }
        }
    }
}
