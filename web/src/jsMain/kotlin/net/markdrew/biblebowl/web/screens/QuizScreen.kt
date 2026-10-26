package net.markdrew.biblebowl.web.screens

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.resolveWithin
import net.markdrew.biblebowl.api.scopeQueryParams
import net.markdrew.biblebowl.generation.quiz.BankQuestion
import net.markdrew.biblebowl.generation.quiz.HeadingQuizMode
import net.markdrew.biblebowl.generation.quiz.PlaceQuestion
import net.markdrew.biblebowl.generation.quiz.QuizEngine
import net.markdrew.biblebowl.generation.quiz.QuizItem
import net.markdrew.biblebowl.generation.quiz.QuizKind
import net.markdrew.biblebowl.generation.quiz.QuizLength
import net.markdrew.biblebowl.generation.quiz.QuizSound
import net.markdrew.biblebowl.generation.quiz.VerseQuizMode
import net.markdrew.biblebowl.generation.quiz.bookShortcutKeys
import net.markdrew.biblebowl.generation.quiz.chapterEntryComplete
import net.markdrew.biblebowl.generation.quiz.headingQuestions
import net.markdrew.biblebowl.generation.quiz.lastChapterIn
import net.markdrew.biblebowl.generation.quiz.verseQuestions
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
import net.markdrew.biblebowl.web.ui.QuizAudio
import net.markdrew.biblebowl.web.ui.chapterChips
import net.markdrew.biblebowl.web.ui.chip
import net.markdrew.biblebowl.web.ui.chipRow
import net.markdrew.biblebowl.web.ui.kbd
import net.markdrew.biblebowl.web.ui.rovingGroup
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent

/** How long a right answer's ✓ shows before the quiz moves on by itself. */
private const val CORRECT_FLASH_MS = 500

/** One quiz kind's own choices and run — kept apart so drilling one never disturbs another. */
private class KindState {
    var round: Round? = null
    var headingMode = HeadingQuizMode.CHOICE
    var verseMode = VerseQuizMode.HEADING_CHOICE
    var length = QuizLength.TWENTY
    var engine: QuizEngine? = null
    var engineTyped = false
    /** The finished quiz whose missed questions are on show (so a new results screen starts hidden). */
    var missedShownFor: QuizEngine? = null
}

/**
 * Quiz mode: setup → stepper → results (with a retry of the misses), one page per [QuizKind] at
 * `#quiz/<section slug>`. All quiz logic lives in QuizEngine. Built to be driven from the keyboard
 * end to end — see [onKey] for the shortcuts, which the screen advertises with keycaps that touch
 * devices hide.
 */
object QuizScreen {

    // Each kind's filters and any in-flight quiz are sticky for the session; the chapter span is
    // shared, so "I'm studying 1–5" carries from one quiz to the next.
    private val states = QuizKind.entries.associateWith { KindState() }
    private var kind = QuizKind.GENERAL
    private val state: KindState get() = states.getValue(kind)
    private var selection = ScopeSelection()
    private var error: String? = null

    /** The keys the current view answers to; reassigned by each render. */
    private var keyHandler: (KeyboardEvent) -> Boolean = { false }
    private var listening = false
    private var advanceTimer: Int? = null

    private lateinit var root: HTMLElement

    fun render(container: HTMLElement, kind: QuizKind, params: Map<String, String> = emptyMap()) {
        if (this.kind != kind) error = null
        this.kind = kind
        // A scoped deep link (#quiz/verses?book=ACT&fromChapter=3&chapter=7) seeds the chapter span.
        // A single-book set's links omit the book, so it's filled back in for the chips to light.
        if (params.isNotEmpty()) {
            selection = ScopeSelection(
                book = params[StudyScopeParams.BOOK]?.let { bookByCode(it) }
                    ?: Session.studySet.books.singleOrNull(),
                chapter = params[StudyScopeParams.CHAPTER]?.toIntOrNull(),
                fromChapter = params[StudyScopeParams.FROM_CHAPTER]?.toIntOrNull(),
            )
        }
        root = container
        listen()
        rerender()
    }

    /**
     * One document-level keydown listener for the screen's lifetime, dispatching to whatever view is
     * showing. It stands down once the quiz is no longer on the page, while a modifier is held, and
     * for keys aimed at anything outside the quiz (the navbar's links and search keep theirs).
     */
    private fun listen() {
        if (listening) return
        listening = true
        document.addEventListener("keydown", { e ->
            val ke = e as KeyboardEvent
            if (ke.defaultPrevented || ke.ctrlKey || ke.metaKey || ke.altKey) return@addEventListener
            if (document.body?.contains(root) != true) return@addEventListener
            val target = ke.target as? Node
            if (target != null && target != document.body && !root.contains(target)) return@addEventListener
            if (keyHandler(ke)) ke.preventDefault()
        })
    }

    private fun rerender() {
        // A pick re-renders the screen; put focus back on the chip that had it.
        val focusKey = (document.activeElement as? HTMLElement)?.getAttribute("data-kbd-id")
        root.clear()
        keyHandler = { false }
        val quiz = state.engine
        when {
            quiz == null -> {
                renderSetup()
                focusKey?.let { (root.querySelector("[data-kbd-id='$it']") as? HTMLElement)?.focus() }
            }
            quiz.isFinished -> renderResults(quiz)
            else -> renderStepper(quiz)
        }
    }

    private fun cancelAdvance() {
        advanceTimer?.let { window.clearTimeout(it) }
        advanceTimer = null
    }

    private fun endQuiz() {
        cancelAdvance()
        state.engine = null
        rerender()
    }

    private fun syncParams() {
        Shell.replaceParams(scopeQueryParams(Session.studySet, selection))
    }

    // --- setup ---

    private fun renderSetup() {
        val state = state
        root.child("h1", "page-title", "${kind.title} quiz")

        when (kind) {
            QuizKind.GENERAL -> {
                root.child("p", "fw-semibold mb-1", "Round")
                root.chipRow(
                    listOf<Pair<String, Round?>>("All" to null) +
                        Round.crowdSourcedRounds.map { it.displayName to it },
                    state.round,
                    keyGroup = "round",
                ) { state.round = it; rerender() }
            }
            QuizKind.HEADINGS -> {
                root.child("p", "fw-semibold mb-1", "Mode")
                root.chipRow(HeadingQuizMode.entries.map { it.label to it }, state.headingMode, keyGroup = "mode") {
                    state.headingMode = it; rerender()
                }
            }
            QuizKind.VERSES -> {
                root.child("p", "fw-semibold mb-1", "Mode")
                root.chipRow(VerseQuizMode.entries.map { it.label to it }, state.verseMode, keyGroup = "mode") {
                    state.verseMode = it; rerender()
                }
            }
        }

        root.child("p", "fw-semibold mb-1", "Chapters")
        root.chapterChips(selection, range = true, keyGroup = "scope") {
            selection = it
            syncParams()
            rerender()
        }

        root.child("p", "fw-semibold mb-1", "Questions")
        root.chipRow(kind.lengths.map { it.label to it }, state.length, keyGroup = "length") {
            state.length = it; rerender()
        }

        val startButton = root.child("button", "btn btn-primary w-100 mt-2", "Start quiz") {
            setAttribute("type", "button")
            onClick { start() }
            kbd("Enter")
        }
        error?.let { root.errorLine(it) }
        root.child(
            "p", "tbb-kbd-hint form-text mt-2",
            "Keyboard: Tab between groups, ← → within one, Space picks, Enter starts. " +
                "In the quiz: A–E answer, Enter continues, Esc ends.",
        )
        startButton.focus()

        keyHandler = { e ->
            // Enter starts from anywhere on the setup — on a chip too (Space is what picks one).
            if (e.key == "Enter") { start(); true } else false
        }
    }

    private fun start() {
        root.clear()
        root.spinner()
        keyHandler = { false }
        error = null
        val kind = kind
        val state = state
        Shell.scope.launch {
            try {
                // Every kind asks for exactly the span the chips light, ends spelled out explicitly.
                val scope = selection.resolveWithin(Session.studySet)
                val book = scope.book?.name
                val from = scope.chapters?.start?.chapter
                val through = scope.chapters?.endInclusive?.chapter
                val multiBook = !Session.studySet.isSingleBook
                val pool = when (kind) {
                    QuizKind.GENERAL ->
                        Session.api.questions(book = book, fromChapter = from, chapter = through)
                            .filter { state.round == null || it.roundType == state.round }
                            .map(::BankQuestion)
                    // The whole set's headings, so a title that recurs outside the span is still known.
                    QuizKind.HEADINGS -> headingQuestions(
                        Session.api.headings(),
                        inScope = scope::covers,
                        multiBook = multiBook,
                        mode = state.headingMode,
                    )
                    QuizKind.VERSES -> coroutineScope {
                        val verses = async {
                            Session.api.verses(state.length.count, book = book, fromChapter = from, throughChapter = through)
                        }
                        val headings = async { Session.api.headings() }
                        verseQuestions(verses.await(), headings.await(), scope::covers, multiBook, state.verseMode)
                    }
                }
                val e = QuizEngine(
                    pool,
                    limit = state.length.count,
                    noneOfTheAbove = (kind == QuizKind.HEADINGS && state.headingMode == HeadingQuizMode.CHOICE_NOTA) ||
                        (kind == QuizKind.VERSES && state.verseMode == VerseQuizMode.HEADING_CHOICE_NOTA),
                )
                if (e.isEmpty) {
                    error = when (kind) {
                        QuizKind.GENERAL -> "No approved questions match — loosen the filters or contribute some!"
                        QuizKind.HEADINGS -> "Chapter headings aren't available right now — please try again later."
                        QuizKind.VERSES -> "Verses aren't available right now — please try again later."
                    }
                } else {
                    state.engine = e
                    state.engineTyped = (kind == QuizKind.HEADINGS && state.headingMode == HeadingQuizMode.TYPED) ||
                        (kind == QuizKind.VERSES && state.verseMode == VerseQuizMode.TYPED)
                }
            } catch (t: Throwable) {
                error = friendlyError(t)
            }
            // Still on this quiz? (A slow fetch can land after the user has moved to another.)
            if (this@QuizScreen.kind == kind) rerender()
        }
    }

    // --- stepper ---

    private fun renderStepper(quiz: QuizEngine) {
        // Left mid-feedback (navigated away before Continue): that item is done — move on rather
        // than offer it again.
        if (quiz.isCurrentAnswered) {
            quiz.next()
            if (quiz.isFinished) return renderResults(quiz)
        }
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
            child("div", "d-flex gap-2") {
                soundToggle()
                // The quiz is sticky across navigation, so this is the way out short of finishing —
                // back to setup with the filters kept, to pick other chapters or another mode.
                child("button", "btn btn-sm btn-outline-secondary", "End quiz") {
                    setAttribute("type", "button")
                    onClick { endQuiz() }
                    kbd("Esc")
                }
            }
        }

        // The focus target while answering by letter: keys land in the quiz, and Tab moves on to
        // the choices rather than starting back at the top of the page.
        val stage = root.child("div") { tabIndex = -1; setAttribute("style", "outline: none;") }
        stage.child("div", "card section-card mb-3") {
            child("div", "card-body") {
                child("div", "text-muted small mb-1", kind.askLine(item))
                child("p", "fs-5 mb-0", item.question.prompt)
            }
        }

        when {
            item.isMultipleChoice -> multipleChoice(quiz, item, stage)
            state.engineTyped -> typedChapter(quiz, item, stage)
            else -> selfGraded(quiz, item, stage)
        }
    }

    /** Turns the answer cues on or off, in place — a re-render would cost an answered item its feedback. */
    private fun HTMLElement.soundToggle() {
        child("button", "btn btn-sm btn-outline-secondary") {
            setAttribute("type", "button")
            setAttribute("aria-label", "Answer sounds")
            fun show() {
                setAttribute("aria-pressed", "${QuizAudio.enabled}")
                setAttribute("title", if (QuizAudio.enabled) "Sounds on" else "Sounds off")
                clear()
                child("i", if (QuizAudio.enabled) "bi bi-volume-up-fill" else "bi bi-volume-mute-fill") {
                    setAttribute("aria-hidden", "true")
                }
            }
            show()
            onClick { QuizAudio.enabled = !QuizAudio.enabled; show() }
        }
    }

    /** Moves on: now, or after the right-answer flash (a stale timer can't advance a later item). */
    private fun advance(quiz: QuizEngine, afterFlash: Boolean = false) {
        cancelAdvance()
        if (!afterFlash) {
            quiz.next(); rerender(); return
        }
        val position = quiz.position
        advanceTimer = window.setTimeout({
            advanceTimer = null
            if (state.engine === quiz && quiz.position == position) {
                quiz.next()
                if (document.body?.contains(root) == true) rerender()
            }
        }, CORRECT_FLASH_MS)
    }

    /**
     * After an answer: a right one chimes, flashes ✓ and moves on by itself (Enter skips the wait); a
     * wrong one plays the falling cue, shows the right answer and waits for Continue. Wording that occurs
     * in more than one place always names every place and waits for Continue, right or wrong.
     */
    private fun feedback(quiz: QuizEngine, item: QuizItem, footer: HTMLElement, correct: Boolean) {
        QuizAudio.play(if (correct) QuizSound.CORRECT else QuizSound.WRONG)
        val refs = item.question.references.joinToString("; ")
        // Wording that occurs in more than one place: every place is named, and the quiz waits for
        // Continue even after a right answer, so the repeat is seen before the next question.
        val repeatNote = (item.question as? PlaceQuestion)?.repeatNote
        if (correct && repeatNote != null) {
            footer.child("p", "tbb-correct-flash fw-semibold text-success fs-5 mb-1", "✓ Correct")
        } else if (correct) {
            footer.child("p", "tbb-correct-flash fw-semibold text-success fs-5 mb-1", "✓ Correct") {
                if (refs.isNotEmpty()) child("span", "small text-muted fw-normal ms-2", refs)
            }
            advance(quiz, afterFlash = true)
            keyHandler = { e ->
                when (e.key) {
                    "Enter" -> { advance(quiz); true }
                    "Escape" -> { endQuiz(); true }
                    else -> false
                }
            }
            return
        }
        if (!correct) footer.child("p", "mb-2") {
            child("span", "text-muted", "Answer: ")
            val answer = if (item.noneIsCorrect) {
                "${QuizEngine.NONE_OF_THE_ABOVE} — ${item.question.answer}"
            } else item.question.answer
            child("span", "fw-semibold", answer) { setAttribute("style", "color:var(--tbb-navy);") }
            if (refs.isNotEmpty()) child("span", "small text-muted ms-2", refs)
        }
        repeatNote?.let { footer.child("p", "small mb-2", it) { setAttribute("role", "status") } }
        val continueButton = footer.child("button", "btn btn-primary w-100", "Continue") {
            setAttribute("type", "button")
            onClick { advance(quiz) }
            kbd("Enter")
        }
        continueButton.focus()
        keyHandler = { e ->
            when (e.key) {
                "Enter" -> { advance(quiz); true }
                "Escape" -> { endQuiz(); true }
                else -> false
            }
        }
    }

    private fun multipleChoice(quiz: QuizEngine, item: QuizItem, stage: HTMLElement) {
        val choicesDiv = stage.child("div", "d-grid gap-2 mb-3")
        val footer = stage.child("div")
        fun pick(i: Int) {
            if (quiz.isCurrentAnswered) return
            val correct = quiz.answerChoice(i)
            // Instant feedback: green on the correct choice, red on a wrong pick.
            choicesDiv.querySelectorAll("button").asList().forEachIndexed { j, node ->
                val b = node as HTMLButtonElement
                b.className = when {
                    j == item.correctIndex -> "btn btn-success text-start"
                    j == i -> "btn btn-danger text-start"
                    else -> "btn btn-outline-secondary text-start"
                }
                // Inert but not `disabled`, which would fade the green/red to Bootstrap's 65%.
                b.setAttribute("aria-disabled", "true")
                b.tabIndex = -1
                b.style.setProperty("pointer-events", "none")
            }
            stage.focus()
            feedback(quiz, item, footer, correct)
        }
        item.choices.forEachIndexed { i, choice ->
            choicesDiv.child("button", "btn btn-outline-primary text-start") {
                setAttribute("type", "button")
                kbd("${'A' + i}", always = true, classes = "me-2")
                appendChild(document.createTextNode(choice))
                onClick { pick(i) }
            }
        }
        stage.focus()
        keyHandler = { e ->
            val key = e.key.uppercase()
            val index = when {
                key.length != 1 -> -1
                key[0] in 'A'..'Z' -> key[0] - 'A'
                key[0] in '1'..'9' -> key[0] - '1'
                else -> -1
            }
            when {
                index in item.choices.indices -> { pick(index); true }
                e.key == "Escape" -> { endQuiz(); true }
                else -> false
            }
        }
    }

    /**
     * Type-the-chapter: pick the book (multi-book sets, by letter or tap) and type the number. It
     * checks itself the moment the digits can only mean one chapter ("3" in a 28-chapter book), so
     * most answers take one or two keystrokes and no Enter.
     */
    private fun typedChapter(quiz: QuizEngine, item: QuizItem, stage: HTMLElement) {
        val set = Session.studySet
        val multiBook = !set.isSingleBook
        // Only the books this quiz draws from; with just one there's nothing to pick.
        val books = quiz.items
            .flatMap { (it.question as? PlaceQuestion)?.books.orEmpty() }
            .distinct().sortedBy { it.ordinal }
            .ifEmpty { set.books }
        val keys = bookShortcutKeys(books)
        var book: Book? = books.singleOrNull()
        val bookRow = if (multiBook && books.size > 1) {
            stage.child("p", "fw-semibold mb-1", "Book")
            stage.child("div", "d-flex flex-wrap gap-1 mb-2")
        } else null

        val answerRow = stage.child("div", "d-flex gap-2 align-items-stretch mb-3")
        answerRow.child("label", "visually-hidden", "Chapter") { setAttribute("for", "tbb-quiz-chapter") }
        val input = answerRow.child("input", "form-control form-control-lg") {
            id = "tbb-quiz-chapter"
            setAttribute("type", "text")
            setAttribute("placeholder", "Chapter")
            setAttribute("inputmode", "numeric")
            setAttribute("autocomplete", "off")
            setAttribute("enterkeyhint", "done")
        } as HTMLInputElement
        val checkButton = answerRow.child("button", "btn btn-primary text-nowrap", "Check") {
            setAttribute("type", "button")
            kbd("Enter")
        } as HTMLButtonElement
        val footer = stage.child("div")

        fun submit() {
            if (quiz.isCurrentAnswered) return
            val chapter = input.value.toIntOrNull() ?: return input.focus()
            if (book == null) {
                footer.clear()
                footer.child("p", "small text-danger mb-2", "Pick the book first.")
                return
            }
            val correct = (item.question as PlaceQuestion).accepts(book, chapter, multiBook)
            quiz.answerSelf(correct)
            input.disabled = true
            checkButton.disabled = true
            bookRow?.querySelectorAll("button")?.asList()?.forEach { (it as HTMLButtonElement).disabled = true }
            input.classList.add(if (correct) "is-valid" else "is-invalid")
            footer.clear()
            stage.focus()
            feedback(quiz, item, footer, correct)
        }

        fun maybeSubmit() {
            val b = book ?: return
            if (chapterEntryComplete(input.value, set.lastChapterIn(b))) submit()
        }

        fun renderBooks() {
            val row = bookRow ?: return
            row.clear()
            books.forEach { b ->
                row.chip(b.briefName, book == b) {
                    book = b; renderBooks(); input.focus(); maybeSubmit()
                }
                (row.lastElementChild as HTMLElement).kbd("${keys[b]}")
            }
            row.rovingGroup("quiz-book")
        }
        renderBooks()

        input.addEventListener("input", {
            // Digits only — the field is the chapter, and letters are book shortcuts.
            val digits = input.value.filter { it.isDigit() }.take(3)
            if (digits != input.value) input.value = digits
            maybeSubmit()
        })
        checkButton.onClick { submit() }
        input.focus()

        keyHandler = handler@{ e ->
            val key = e.key
            when {
                key == "Escape" -> { endQuiz(); true }
                key == "Enter" -> { submit(); true }
                key.length == 1 && key[0].isLetter() -> {
                    val b = keys.entries.firstOrNull { it.value == key[0].uppercaseChar() }?.key
                        ?: return@handler true // swallow stray letters so they never reach the field
                    if (bookRow != null) { book = b; renderBooks(); input.focus(); maybeSubmit() }
                    true
                }
                // Digits typed while focus sits elsewhere (a book chip) still go to the field.
                key.length == 1 && key[0].isDigit() && e.target != input -> {
                    input.value = (input.value + key).take(3); input.focus(); maybeSubmit(); true
                }
                else -> false
            }
        }
    }

    /** Short-answer bank items: reveal, then grade yourself. */
    private fun selfGraded(quiz: QuizEngine, item: QuizItem, stage: HTMLElement) {
        val slot = stage.child("div")
        fun grade(gotIt: Boolean) {
            quiz.answerSelf(gotIt); advance(quiz)
        }
        fun reveal() {
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
                    onClick { grade(true) }
                    kbd("Y")
                }
                child("button", "btn btn-outline-primary flex-fill", "I missed it") {
                    setAttribute("type", "button")
                    onClick { grade(false) }
                    kbd("N")
                }
            }
            stage.focus()
            keyHandler = { e ->
                when (e.key.lowercase()) {
                    "y", "1" -> { grade(true); true }
                    "n", "2" -> { grade(false); true }
                    "escape" -> { endQuiz(); true }
                    else -> false
                }
            }
        }
        slot.child("button", "btn btn-primary w-100", "Reveal answer") {
            setAttribute("type", "button")
            onClick { reveal() }
            kbd("Enter")
        }
        stage.focus()
        keyHandler = { e ->
            when (e.key) {
                "Enter" -> { reveal(); true }
                "Escape" -> { endQuiz(); true }
                else -> false
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
        val missed = result.missed
        val state = state
        val showMissed = state.missedShownFor === quiz && missed.isNotEmpty()
        fun retry() { state.engine = quiz.retryMissed(); rerender() }
        fun newQuiz() { state.engine = null; rerender() }
        fun toggleMissed() {
            state.missedShownFor = if (showMissed) null else quiz
            rerender()
            (root.querySelector("[data-tbb-review]") as? HTMLElement)?.focus()
        }

        val buttons = root.child("div", "d-grid gap-2 mt-3")
        val retryButton = if (missed.isEmpty()) null else {
            buttons.child("button", "btn btn-primary", "Retry the ${missed.size} missed") {
                setAttribute("type", "button")
                onClick { retry() }
                kbd("R")
            }
        }
        if (missed.isNotEmpty()) {
            buttons.child("button", "btn btn-outline-primary", if (showMissed) "Hide missed" else "Review missed") {
                setAttribute("type", "button")
                setAttribute("data-tbb-review", "")
                setAttribute("aria-expanded", "$showMissed")
                onClick { toggleMissed() }
                kbd("V")
            }
        }
        val newButton = buttons.child(
            "button", if (retryButton == null) "btn btn-primary" else "btn btn-outline-primary", "New quiz",
        ) {
            setAttribute("type", "button")
            onClick { newQuiz() }
            kbd("N")
        }
        (retryButton ?: newButton).focus()

        if (showMissed) {
            root.child("p", "fw-semibold mt-4 mb-2", "Missed questions")
            missed.forEach { item ->
                root.child("div", "card section-card mb-2") {
                    child("div", "card-body py-2") {
                        child("p", "mb-1", item.question.prompt)
                        child("p", "fw-semibold mb-0", item.question.answer) {
                            setAttribute("style", "color:var(--tbb-navy);")
                        }
                        if (item.question.references.isNotEmpty()) {
                            child("p", "small text-muted mb-0", item.question.references.joinToString("; "))
                        }
                        (item.question as? PlaceQuestion)?.repeatNote?.let { child("p", "small text-muted mb-0", it) }
                    }
                }
            }
        }

        keyHandler = { e ->
            when (e.key.lowercase()) {
                // A focused button (Review missed, New quiz) answers Enter itself.
                "enter" -> if (document.activeElement is HTMLButtonElement) false else {
                    if (missed.isNotEmpty()) retry() else newQuiz(); true
                }
                "r" -> if (missed.isNotEmpty()) { retry(); true } else false
                "v" -> if (missed.isNotEmpty()) { toggleMissed(); true } else false
                "n" -> { newQuiz(); true }
                else -> false
            }
        }
    }
}
