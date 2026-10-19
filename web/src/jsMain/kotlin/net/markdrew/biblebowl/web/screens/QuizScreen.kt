package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.resolveWithin
import net.markdrew.biblebowl.api.scopeQueryParams
import net.markdrew.biblebowl.generation.quiz.QuizEngine
import net.markdrew.biblebowl.model.Book
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.bookByCode
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.friendlyError
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import net.markdrew.biblebowl.web.ui.chapterChips
import net.markdrew.biblebowl.web.ui.chipRow
import org.w3c.dom.HTMLElement

/** What a quiz draws from: the community question bank, or ESV chapter headings (Round 5). */
private enum class QuizSource(val displayName: String) {
    QUESTIONS("Question bank"),
    HEADINGS("Chapter headings"),
}

/**
 * Turns the season's headings into multiple-choice "which chapter?" items. Distractors are other
 * chapters in scope, so a drill over a chapter span never offers chapters outside it.
 * Chapter labels stay "Chapter N" for single-book sets and gain the book ("Num 14") for multi-book
 * sets, where a bare number would be ambiguous.
 */
private fun headingQuestions(headings: List<HeadingDto>): List<QuestionDto> {
    val multiBook = !Session.studySet.isSingleBook
    fun label(h: HeadingDto): String {
        val book = h.bookCode?.takeIf { multiBook }?.let { code -> Book.entries.firstOrNull { it.name == code } }
        return book?.let { "${it.briefName} ${h.chapter}" } ?: "Chapter ${h.chapter}"
    }
    val chaptersInScope = headings.map(::label).distinct()
    return headings.map { h ->
        val distractors = (chaptersInScope - label(h)).shuffled().take(4)
        QuestionDto(
            id = "heading-${h.index}",
            roundType = Round.EVENTS,
            prompt = "Which chapter has the heading “${h.title}”?",
            answer = label(h),
            references = listOf(h.reference),
            choices = distractors + label(h),
            bookCode = h.bookCode,
            chapter = h.chapter,
            status = QuestionStatus.APPROVED,
            authorId = "esv-headings",
        )
    }
}

/** Quiz mode: setup → stepper with instant feedback → results. All quiz logic lives in QuizEngine. */
object QuizScreen {

    // Setup filters and any in-flight quiz are sticky for the session.
    private var source = QuizSource.QUESTIONS
    private var round: Round? = null
    private var selection = ScopeSelection()
    private var engine: QuizEngine? = null
    private var error: String? = null

    private lateinit var root: HTMLElement

    fun render(container: HTMLElement, params: Map<String, String> = emptyMap()) {
        // A scoped deep link (#quiz?book=ACT&fromChapter=3&chapter=7) seeds the setup filters. A
        // single-book set's links omit the book, so it's filled back in for the chips to light.
        if (params.isNotEmpty()) {
            selection = ScopeSelection(
                book = params[StudyScopeParams.BOOK]?.let { bookByCode(it) }
                    ?: Session.studySet.books.singleOrNull(),
                chapter = params[StudyScopeParams.CHAPTER]?.toIntOrNull(),
                fromChapter = params[StudyScopeParams.FROM_CHAPTER]?.toIntOrNull(),
            )
        }
        root = container
        rerender()
    }

    private fun rerender() {
        root.clear()
        val quiz = engine
        when {
            quiz == null -> renderSetup()
            quiz.isFinished -> renderResults(quiz)
            else -> renderStepper(quiz)
        }
    }

    // --- setup ---

    private fun renderSetup() {
        root.child("h1", "page-title", "Quiz me")

        root.child("p", "fw-semibold mb-1", "Source")
        root.chipRow(QuizSource.entries.map { it.displayName to it }, source) {
            source = it; rerender()
        }

        if (source == QuizSource.QUESTIONS) {
            root.child("p", "fw-semibold mb-1", "Round")
            root.chipRow(
                listOf<Pair<String, Round?>>("All" to null) + Round.crowdSourcedRounds.map { it.displayName to it },
                round,
            ) { round = it; rerender() }
        }

        root.child("p", "fw-semibold mb-1", "Chapters")
        root.chapterChips(selection, range = true) {
            selection = it
            Shell.replaceParams(scopeQueryParams(Session.studySet, it))
            rerender()
        }

        root.child("button", "btn btn-primary w-100 mt-2", "Start quiz") {
            setAttribute("type", "button")
            onClick { start() }
        }
        error?.let { root.errorLine(it) }
    }

    private fun start() {
        root.clear()
        root.spinner()
        error = null
        Shell.scope.launch {
            try {
                // Both sources ask for exactly the span the chips light, ends spelled out explicitly.
                val scope = selection.resolveWithin(Session.studySet)
                val book = scope.book?.name
                val from = scope.chapters?.start?.chapter
                val through = scope.chapters?.endInclusive?.chapter
                val pool = when (source) {
                    QuizSource.QUESTIONS ->
                        Session.api.questions(book = book, fromChapter = from, chapter = through)
                            .filter { round == null || it.roundType == round }
                    QuizSource.HEADINGS -> headingQuestions(
                        Session.api.headings(book = book, fromChapter = from, throughChapter = through),
                    )
                }
                val e = QuizEngine(pool)
                if (e.isEmpty) {
                    error = when (source) {
                        QuizSource.QUESTIONS -> "No approved questions match — loosen the filters or contribute some!"
                        QuizSource.HEADINGS -> "Chapter headings aren't available right now — please try again later."
                    }
                } else {
                    engine = e
                }
            } catch (t: Throwable) {
                error = friendlyError(t)
            }
            rerender()
        }
    }

    // --- stepper ---

    private fun renderStepper(quiz: QuizEngine) {
        val item = quiz.current ?: return

        root.child("div", "progress mb-2") {
            setAttribute("style", "height: 6px;")
            child("div", "progress-bar") {
                setAttribute("style", "width: ${(quiz.position - 1) * 100 / quiz.total}%; background-color:var(--tbb-navy);")
            }
        }
        root.child("div", "d-flex justify-content-between align-items-center mb-3") {
            child("span", "fw-semibold small", "${quiz.position} of ${quiz.total}")
            child("span", "fw-semibold small tbb-gold", "Score: ${quiz.scoreSoFar}")
            // The quiz is sticky across navigation, so this is the way out short of finishing —
            // back to setup with the filters kept, to pick other chapters or another source.
            child("button", "btn btn-sm btn-outline-secondary", "End quiz") {
                setAttribute("type", "button")
                onClick { engine = null; rerender() }
            }
        }

        root.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("div", "text-muted small mb-1", item.question.roundType.displayName)
                child("p", "fs-5 mb-0", item.question.prompt)
            }
        }

        if (item.isMultipleChoice) {
            var picked: Int? = null
            val choicesDiv = root.child("div", "d-grid gap-2 mb-3")
            val footer = root.child("div")
            item.choices.forEachIndexed { i, choice ->
                val btn = choicesDiv.child("button", "btn btn-outline-primary text-start", "${'A' + i}. $choice") {
                    setAttribute("type", "button")
                }
                btn.onClick {
                    if (picked != null) return@onClick
                    picked = i
                    quiz.answerChoice(i)
                    // Instant feedback: green on the correct choice, red on a wrong pick.
                    choicesDiv.querySelectorAll("button").let { buttons ->
                        for (j in 0 until buttons.length) {
                            val b = buttons.item(j) as HTMLElement
                            b.className = when {
                                j == item.correctIndex -> "btn btn-success text-start"
                                j == i -> "btn btn-danger text-start"
                                else -> "btn btn-outline-secondary text-start"
                            }
                            b.setAttribute("disabled", "")
                        }
                    }
                    // Where to find the answer, right away rather than only in the end-of-quiz review.
                    if (item.question.references.isNotEmpty()) {
                        footer.child("p", "small text-muted mb-2", item.question.references.joinToString("; "))
                    }
                    footer.child("button", "btn btn-primary w-100", "Next") {
                        setAttribute("type", "button")
                        onClick { quiz.next(); rerender() }
                    }
                }
            }
        } else {
            val slot = root.child("div")
            slot.child("button", "btn btn-primary w-100", "Reveal answer") {
                setAttribute("type", "button")
                onClick {
                    slot.clear()
                    slot.child("div", "card section-card mb-3") {
                        child("div", "card-body") {
                            child("p", "fw-semibold fs-5 mb-1", item.question.answer) {
                                setAttribute("style", "color:var(--tbb-navy);")
                            }
                            if (item.question.references.isNotEmpty()) {
                                child("p", "small mb-0", item.question.references.joinToString("; "))
                            }
                        }
                    }
                    slot.child("div", "d-flex gap-2") {
                        child("button", "btn btn-primary flex-fill", "I got it") {
                            setAttribute("type", "button")
                            onClick { quiz.answerSelf(true); quiz.next(); rerender() }
                        }
                        child("button", "btn btn-outline-primary flex-fill", "I missed it") {
                            setAttribute("type", "button")
                            onClick { quiz.answerSelf(false); quiz.next(); rerender() }
                        }
                    }
                }
            }
        }
    }

    // --- results ---

    private fun renderResults(quiz: QuizEngine) {
        val result = quiz.result()
        root.child("div", "text-center") {
            child("h2", "mt-3", "Quiz complete!")
            child("div", "display-4 fw-bold", "${result.score} / ${result.total}") {
                setAttribute("style", "color:var(--tbb-navy);")
            }
        }
        if (result.missed.isNotEmpty()) {
            root.child("p", "fw-semibold mt-3 mb-2", "Review these:")
            result.missed.forEach { item ->
                root.child("div", "card section-card mb-2") {
                    child("div", "card-body py-2") {
                        child("p", "mb-1", item.question.prompt)
                        child("p", "fw-semibold mb-0", item.question.answer) {
                            setAttribute("style", "color:var(--tbb-navy);")
                        }
                        if (item.question.references.isNotEmpty()) {
                            child("p", "small text-muted mb-0", item.question.references.joinToString("; "))
                        }
                    }
                }
            }
        }
        root.child("button", "btn btn-primary w-100 mt-3", "New quiz") {
            setAttribute("type", "button")
            onClick { engine = null; rerender() }
        }
    }
}
