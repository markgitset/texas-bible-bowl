package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.generation.quiz.QuizEngine
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
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
 * chapters in scope, so cumulative practice (through chapter N) never leaks future chapters.
 */
private fun headingQuestions(headings: List<HeadingDto>): List<QuestionDto> {
    val chaptersInScope = headings.map { it.chapter }.distinct()
    return headings.map { h ->
        val distractors = (chaptersInScope - h.chapter).shuffled().take(4)
        QuestionDto(
            id = "heading-${h.index}",
            roundType = Round.EVENTS,
            prompt = "Which chapter has the heading “${h.title}”?",
            answer = "Chapter ${h.chapter}",
            references = listOf(h.reference),
            choices = (distractors + h.chapter).map { "Chapter $it" },
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
    private var chapter: Int? = null
    private var engine: QuizEngine? = null
    private var error: String? = null

    private lateinit var root: HTMLElement

    fun render(container: HTMLElement) {
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

        root.child("p", "fw-semibold mb-1", if (source == QuizSource.HEADINGS) "Through chapter" else "Chapter")
        root.chapterChips(chapter) { chapter = it; rerender() }

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
                val pool = when (source) {
                    QuizSource.QUESTIONS -> Session.api.questions(chapter = chapter)
                        .filter { round == null || it.roundType == round }
                    // Chapter chip means "through chapter N" here so drills stay cumulative.
                    QuizSource.HEADINGS -> headingQuestions(Session.api.headings(throughChapter = chapter))
                }
                val e = QuizEngine(pool)
                if (e.isEmpty) {
                    error = when (source) {
                        QuizSource.QUESTIONS -> "No approved questions match — loosen the filters or contribute some!"
                        QuizSource.HEADINGS -> "No headings available — is the server's ESV service configured?"
                    }
                } else {
                    engine = e
                }
            } catch (t: Throwable) {
                error = t.message
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
        root.child("div", "d-flex justify-content-between mb-3") {
            child("span", "fw-semibold small", "${quiz.position} of ${quiz.total}")
            child("span", "fw-semibold small tbb-gold", "Score: ${quiz.scoreSoFar}")
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
