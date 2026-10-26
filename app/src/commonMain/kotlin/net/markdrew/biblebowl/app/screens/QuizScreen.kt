package net.markdrew.biblebowl.app.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.ScopeSelection
import net.markdrew.biblebowl.api.resolveWithin
import net.markdrew.biblebowl.api.resolvedStudySet
import net.markdrew.biblebowl.app.platform.QuizAudio
import net.markdrew.biblebowl.app.platform.hasHardwareKeyboard
import net.markdrew.biblebowl.app.ui.ChapterChips
import net.markdrew.biblebowl.app.ui.LocalSeason
import net.markdrew.biblebowl.client.TbbApi
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
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.bookByCode

/** One quiz kind's own choices and run — kept apart so drilling one never disturbs another. */
class QuizKindState {
    var round by mutableStateOf<Round?>(null)
    var headingMode by mutableStateOf(HeadingQuizMode.CHOICE)
    var verseMode by mutableStateOf(VerseQuizMode.HEADING_CHOICE)
    var length by mutableStateOf(QuizLength.TWENTY)
    /** The run in progress — held here, not in the screen, so leaving the tab doesn't lose it. */
    var engine by mutableStateOf<QuizEngine?>(null)
    var typed by mutableStateOf(false)
}

/**
 * Quiz setup choices, sticky across tab switches: each [QuizKind] keeps its own, while the chapter
 * span is shared so "I'm studying 1–5" carries from one quiz to the next.
 */
object QuizChoices {
    var selection by mutableStateOf(ScopeSelection())
    private val kinds = QuizKind.entries.associateWith { QuizKindState() }
    fun of(kind: QuizKind): QuizKindState = kinds.getValue(kind)
}

/** How long a right answer's ✓ shows before the quiz moves on by itself. */
private const val CORRECT_FLASH_MS = 500L

/**
 * The Quiz tab's landing page: one card per quiz, each opening its own screen ([QuizScreen]). The
 * quizzes never switch kind in place, so this (or a study section's card) is the way between them.
 */
@Composable
fun QuizChooserScreen(onOpen: (QuizKind) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Quiz me", style = MaterialTheme.typography.titleLarge)
        QuizKind.entries.forEach { kind ->
            ElevatedCard(onClick = { onOpen(kind) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (kind) {
                            QuizKind.GENERAL -> "Drill the community question bank (Rounds 2 and 3)."
                            QuizKind.HEADINGS -> "Name the chapter for each ESV section heading (Round 5)."
                            QuizKind.VERSES -> "Place a random verse — pick its heading or type its chapter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One quiz: setup → stepper → results (with a retry of the misses). All quiz logic lives in
 * QuizEngine. On desktop the whole run is keyboard-driven (see each view's key handler) and the
 * shortcuts are shown as keycaps; Android gets neither.
 */
@Composable
fun QuizScreen(api: TbbApi, kind: QuizKind) {
    val state = QuizChoices.of(kind)
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped on every answer/advance so Compose re-reads the engine's state.
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val season = LocalSeason.current

    val quiz = state.engine
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        quiz == null -> QuizSetup(
            kind = kind,
            error = error,
            onStart = {
                loading = true; error = null
                scope.launch {
                    try {
                        // Every kind asks for exactly the span the chips light, ends spelled out explicitly.
                        val set = season.resolvedStudySet
                        val scoped = QuizChoices.selection.resolveWithin(set)
                        val book = scoped.book?.name
                        val from = scoped.chapters?.start?.chapter
                        val through = scoped.chapters?.endInclusive?.chapter
                        val multiBook = !set.isSingleBook
                        val pool = when (kind) {
                            QuizKind.GENERAL ->
                                api.questions(book = book, fromChapter = from, chapter = through)
                                    .filter { state.round == null || it.roundType == state.round }
                                    .map(::BankQuestion)
                            // The whole set's headings, so a title that recurs outside the span is still known.
                            QuizKind.HEADINGS -> headingQuestions(
                                api.headings(),
                                inScope = scoped::covers,
                                multiBook = multiBook,
                                mode = state.headingMode,
                            )
                            QuizKind.VERSES -> coroutineScope {
                                val verses = async {
                                    api.verses(state.length.count, book = book, fromChapter = from, throughChapter = through)
                                }
                                val headings = async { api.headings() }
                                verseQuestions(verses.await(), headings.await(), scoped::covers, multiBook, state.verseMode)
                            }
                        }
                        val e = QuizEngine(
                            pool,
                            limit = state.length.count,
                            noneOfTheAbove = (kind == QuizKind.HEADINGS && state.headingMode == HeadingQuizMode.CHOICE_NOTA) ||
                                (kind == QuizKind.VERSES && state.verseMode == VerseQuizMode.HEADING_CHOICE_NOTA),
                        )
                        if (e.isEmpty) error = when (kind) {
                            QuizKind.GENERAL -> "No approved questions match — loosen the filters or contribute some!"
                            QuizKind.HEADINGS -> "No headings available — is the server's ESV service configured?"
                            QuizKind.VERSES -> "No verses available — is the server's ESV service configured?"
                        }
                        else {
                            state.typed = (kind == QuizKind.HEADINGS && state.headingMode == HeadingQuizMode.TYPED) ||
                                (kind == QuizKind.VERSES && state.verseMode == VerseQuizMode.TYPED)
                            state.engine = e
                        }
                    } catch (t: Throwable) {
                        error = t.message
                    } finally {
                        loading = false
                    }
                }
            },
        )

        quiz.isFinished -> QuizResults(
            quiz,
            onRetry = { state.engine = quiz.retryMissed() },
            onRestart = { state.engine = null },
        )

        else -> QuizStepper(kind, quiz, state.typed, tick, onTick = { tick++ }, onEnd = { state.engine = null })
    }
}

/**
 * Takes keyboard focus for a view (desktop only) and routes its key presses to [onKey], which
 * returns whether it consumed one. Preview events, so a focused text field or button inside the
 * view still lets the view see the key first. [focusKey] re-requests focus when the view's subject
 * changes; [takeFocus] = false leaves focus to a child (the chapter field).
 */
@Composable
private fun keyboardScope(focusKey: Any?, takeFocus: Boolean = true, onKey: (KeyEvent) -> Boolean): Modifier {
    if (!hasHardwareKeyboard) return Modifier
    val requester = remember { FocusRequester() }
    if (takeFocus) LaunchedEffect(focusKey) { runCatching { requester.requestFocus() } }
    return Modifier
        .focusRequester(requester)
        .focusable()
        .onPreviewKeyEvent { e ->
            e.type == KeyEventType.KeyDown && !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed && onKey(e)
        }
}

/** The typed character of [this] key press, uppercased (or null for keys that type nothing). */
private val KeyEvent.char: Char?
    get() = utf16CodePoint.takeIf { it > 0 }?.toChar()?.uppercaseChar()

/** A small keycap naming a keyboard shortcut; desktop only. */
@Composable
private fun KeyHint(key: String, modifier: Modifier = Modifier.padding(start = 8.dp)) {
    if (!hasHardwareKeyboard) return
    val color = LocalContentColor.current.copy(alpha = 0.75f)
    Text(
        key,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
    )
}

@Composable
private fun QuizSetup(kind: QuizKind, error: String?, onStart: () -> Unit) {
    val c = QuizChoices.of(kind)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .then(keyboardScope(Unit) { e -> if (e.key == Key.Enter) { onStart(); true } else false }),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${kind.title} quiz", style = MaterialTheme.typography.titleLarge)
        when (kind) {
            QuizKind.GENERAL -> {
                Text("Round", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = c.round == null, onClick = { c.round = null }, label = { Text("All") })
                    Round.crowdSourcedRounds.forEach { rt ->
                        FilterChip(
                            selected = c.round == rt,
                            onClick = { c.round = if (c.round == rt) null else rt },
                            label = { Text(rt.displayName) },
                        )
                    }
                }
            }
            QuizKind.HEADINGS -> {
                Text("Mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeadingQuizMode.entries.forEach { m ->
                        FilterChip(selected = c.headingMode == m, onClick = { c.headingMode = m }, label = { Text(m.label) })
                    }
                }
            }
            QuizKind.VERSES -> {
                Text("Mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VerseQuizMode.entries.forEach { m ->
                        FilterChip(selected = c.verseMode == m, onClick = { c.verseMode = m }, label = { Text(m.label) })
                    }
                }
            }
        }
        Text("Chapters", style = MaterialTheme.typography.labelLarge)
        ChapterChips(selected = QuizChoices.selection, range = true, onSelect = { QuizChoices.selection = it })
        Text("Questions", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kind.lengths.forEach { n ->
                FilterChip(selected = c.length == n, onClick = { c.length = n }, label = { Text(n.label) })
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start quiz")
            KeyHint("Enter")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (hasHardwareKeyboard) {
            Text(
                "Keyboard: Enter starts. In the quiz: A–E answer, Enter continues, Esc ends.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuizStepper(kind: QuizKind, quiz: QuizEngine, typed: Boolean, tick: Int, onTick: () -> Unit, onEnd: () -> Unit) {
    // tick is read so answering/advancing recomposes this screen.
    @Suppress("UNUSED_EXPRESSION") tick
    val item = quiz.current ?: return
    val advance = { quiz.next(); onTick() }

    // Per-item answer state.
    var picked by remember(item) { mutableStateOf<Int?>(null) }
    var revealed by remember(item) { mutableStateOf(false) }
    var typedText by remember(item) { mutableStateOf("") }
    var verdict by remember(item) { mutableStateOf<Boolean?>(null) }
    var needBook by remember(item) { mutableStateOf(false) }

    // Type-the-chapter: only the books this quiz draws from; with just one there's nothing to pick.
    val set = LocalSeason.current.resolvedStudySet
    val books = remember(quiz) {
        quiz.items.flatMap { (it.question as? PlaceQuestion)?.books.orEmpty() }.distinct().sortedBy { it.ordinal }
            .ifEmpty { set.books }
    }
    val bookKeys = remember(books) { bookShortcutKeys(books) }
    var book by remember(item) { mutableStateOf(books.singleOrNull()) }

    // Machine-graded answers chime or fall; self-graded ones stay quiet (the student already knows).
    fun cue(correct: Boolean) = QuizAudio.play(if (correct) QuizSound.CORRECT else QuizSound.WRONG)
    fun pick(i: Int) {
        if (picked != null) return
        picked = i
        verdict = quiz.answerChoice(i).also(::cue)
        onTick()
    }
    fun submitTyped() {
        if (verdict != null) return
        val chapter = typedText.toIntOrNull() ?: return
        val b = book ?: run { needBook = true; return }
        val ok = (item.question as PlaceQuestion).accepts(b, chapter, multiBook = !set.isSingleBook)
        quiz.answerSelf(ok)
        verdict = ok
        cue(ok)
        onTick()
    }
    fun maybeSubmitTyped() {
        val b = book ?: return
        if (chapterEntryComplete(typedText, set.lastChapterIn(b))) submitTyped()
    }
    fun gradeSelf(gotIt: Boolean) {
        quiz.answerSelf(gotIt); advance()
    }

    // A right answer flashes ✓ and moves on by itself; leaving the screen cancels the wait. Wording that
    // occurs in more than one place waits for Continue instead, so its places are read first.
    val repeatNote = (item.question as? PlaceQuestion)?.repeatNote
    if (verdict == true && repeatNote == null) LaunchedEffect(item) { delay(CORRECT_FLASH_MS); advance() }

    // Typing goes to the chapter field; everything else (and Continue after a typed answer, once
    // the field is disabled) keys off the view itself.
    val keys = keyboardScope(item to verdict, takeFocus = !typed || verdict != null) { e ->
        val ch = e.char
        when {
            e.key == Key.Escape -> { onEnd(); true }
            verdict != null -> if (e.key == Key.Enter) { advance(); true } else false
            item.isMultipleChoice -> {
                val index = when (ch) {
                    null -> -1
                    in 'A'..'Z' -> ch - 'A'
                    in '1'..'9' -> ch - '1'
                    else -> -1
                }
                if (index in item.choices.indices) { pick(index); true } else false
            }
            typed -> when {
                e.key == Key.Enter -> { submitTyped(); true }
                ch != null && ch.isLetter() -> {
                    bookKeys.entries.firstOrNull { it.value == ch }?.key?.let { book = it; needBook = false; maybeSubmitTyped() }
                    true // letters never reach the chapter field
                }
                else -> false
            }
            !revealed -> if (e.key == Key.Enter) { revealed = true; true } else false
            else -> when (ch) {
                'Y', '1' -> { gradeSelf(true); true }
                'N', '2' -> { gradeSelf(false); true }
                else -> false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(keys),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LinearProgressIndicator(
            progress = { (quiz.position - 1).toFloat() / quiz.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${quiz.position} of ${quiz.total}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text("Score: ${quiz.scoreSoFar}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { QuizAudio.enabled = !QuizAudio.enabled }) {
                Icon(
                    if (QuizAudio.enabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (QuizAudio.enabled) "Answer sounds on" else "Answer sounds off",
                )
            }
            // The way out short of finishing — back to setup with the filters kept.
            TextButton(onClick = onEnd) { Text("End quiz"); KeyHint("Esc") }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(kind.askLine(item), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
                Text(item.question.prompt, style = MaterialTheme.typography.titleMedium)
            }
        }

        when {
            item.isMultipleChoice -> {
                item.choices.forEachIndexed { i, choice ->
                    val answered = picked != null
                    val colors = when {
                        !answered -> MaterialTheme.colorScheme.surface
                        i == item.correctIndex -> MaterialTheme.colorScheme.primaryContainer
                        i == picked -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                    OutlinedButton(
                        onClick = { pick(i) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = colors),
                    ) {
                        Text("${'A' + i}. $choice", modifier = Modifier.fillMaxWidth())
                    }
                }
                verdict?.let { Feedback(item, it, onContinue = advance) }
            }

            typed -> {
                if (books.size > 1) {
                    Text("Book", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        books.forEach { b ->
                            FilterChip(
                                selected = book == b,
                                enabled = verdict == null,
                                onClick = { book = b; needBook = false; maybeSubmitTyped() },
                                label = { Text(b.briefName); bookKeys[b]?.let { KeyHint("$it") } },
                            )
                        }
                    }
                }
                val field = remember { FocusRequester() }
                LaunchedEffect(item) { runCatching { field.requestFocus() } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typedText,
                        onValueChange = { v ->
                            // Digits only — the field is the chapter, and letters are book shortcuts.
                            typedText = v.filter(Char::isDigit).take(3)
                            maybeSubmitTyped()
                        },
                        label = { Text("Chapter") },
                        singleLine = true,
                        enabled = verdict == null,
                        isError = verdict == false,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitTyped() }),
                        modifier = Modifier.weight(1f).focusRequester(field),
                    )
                    Button(onClick = ::submitTyped, enabled = verdict == null) { Text("Check"); KeyHint("Enter") }
                }
                if (needBook) Text("Pick the book first.", color = MaterialTheme.colorScheme.error)
                verdict?.let { Feedback(item, it, onContinue = advance) }
            }

            !revealed -> Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Reveal answer"); KeyHint("Enter")
            }

            else -> {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.question.answer, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        if (item.question.references.isNotEmpty()) {
                            Text(item.question.references.joinToString("; "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { gradeSelf(true) }, modifier = Modifier.weight(1f)) {
                        Text("I got it"); KeyHint("Y")
                    }
                    OutlinedButton(onClick = { gradeSelf(false) }, modifier = Modifier.weight(1f)) {
                        Text("I missed it"); KeyHint("N")
                    }
                }
            }
        }
    }
}

/**
 * After an answer: a right one shows a quick ✓ (the stepper moves on by itself); a wrong one shows
 * the right answer and waits for Continue. Wording that occurs in more than one place always names
 * every place and waits for Continue, right or wrong.
 */
@Composable
private fun Feedback(item: QuizItem, correct: Boolean, onContinue: () -> Unit) {
    val refs = item.question.references.joinToString("; ")
    val repeatNote = (item.question as? PlaceQuestion)?.repeatNote
    if (correct && repeatNote != null) {
        Text("✓ Correct", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2E7D32))
    } else if (correct) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓ Correct", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF2E7D32))
            if (refs.isNotEmpty()) {
                Text(refs, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (!correct) {
        val answer = if (item.noneIsCorrect) "${QuizEngine.NONE_OF_THE_ABOVE} — ${item.question.answer}" else item.question.answer
        Text("Answer: $answer", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        if (refs.isNotEmpty()) Text(refs, style = MaterialTheme.typography.bodySmall)
    }
    repeatNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue"); KeyHint("Enter") }
}

@Composable
private fun QuizResults(quiz: QuizEngine, onRetry: () -> Unit, onRestart: () -> Unit) {
    val result = quiz.result()
    val missed = result.missed
    var showMissed by remember(quiz) { mutableStateOf(false) }
    val keys = keyboardScope(quiz) { e ->
        when {
            e.key == Key.Enter -> { if (missed.isNotEmpty()) onRetry() else onRestart(); true }
            e.char == 'R' && missed.isNotEmpty() -> { onRetry(); true }
            e.char == 'V' && missed.isNotEmpty() -> { showMissed = !showMissed; true }
            e.char == 'N' -> { onRestart(); true }
            else -> false
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(keys),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Quiz complete!", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${result.score} / ${result.total}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (missed.isNotEmpty()) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry the ${missed.size} missed"); KeyHint("R")
            }
            OutlinedButton(onClick = { showMissed = !showMissed }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showMissed) "Hide missed" else "Review missed"); KeyHint("V")
            }
            OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("New quiz"); KeyHint("N") }
            if (showMissed) {
                Text("Missed questions", style = MaterialTheme.typography.titleMedium)
                missed.forEach { item ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.question.prompt, style = MaterialTheme.typography.bodyLarge)
                            Text(item.question.answer, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary)
                            if (item.question.references.isNotEmpty()) {
                                Text(item.question.references.joinToString("; "),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            (item.question as? PlaceQuestion)?.repeatNote?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("New quiz"); KeyHint("N") }
        }
        Spacer(Modifier.height(12.dp))
    }
}
