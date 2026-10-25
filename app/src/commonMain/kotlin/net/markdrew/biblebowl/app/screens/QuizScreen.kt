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
import net.markdrew.biblebowl.generation.quiz.HeadingQuizMode
import net.markdrew.biblebowl.generation.quiz.QuizEngine
import net.markdrew.biblebowl.generation.quiz.QuizItem
import net.markdrew.biblebowl.generation.quiz.QuizLength
import net.markdrew.biblebowl.generation.quiz.QuizSound
import net.markdrew.biblebowl.generation.quiz.bookShortcutKeys
import net.markdrew.biblebowl.generation.quiz.chapterEntryComplete
import net.markdrew.biblebowl.generation.quiz.headingQuestions
import net.markdrew.biblebowl.generation.quiz.lastChapterIn
import net.markdrew.biblebowl.generation.quiz.typedAnswerCorrect
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.model.bookByCode

/** What a quiz draws from: the community question bank, or ESV chapter headings (Round 5). */
enum class QuizSource(val displayName: String) {
    QUESTIONS("Question bank"),
    HEADINGS("Chapter headings"),
}

/**
 * Quiz setup choices, sticky across tab switches — and settable from outside, so the study cards
 * can open Quiz on the source they promise (e.g. Chapter Headings → "Quiz headings").
 */
object QuizChoices {
    var source by mutableStateOf(QuizSource.QUESTIONS)
    var round by mutableStateOf<Round?>(null)
    var headingMode by mutableStateOf(HeadingQuizMode.CHOICE)
    var length by mutableStateOf(QuizLength.TWENTY)
    var selection by mutableStateOf(ScopeSelection())
}

/** How long a right answer's ✓ shows before the quiz moves on by itself. */
private const val CORRECT_FLASH_MS = 800L

/**
 * Quiz mode: setup → stepper → results (with a retry of the misses). All quiz logic lives in
 * QuizEngine. On desktop the whole run is keyboard-driven (see each view's key handler) and the
 * shortcuts are shown as keycaps; Android gets neither.
 */
@Composable
fun QuizScreen(api: TbbApi) {
    var engine by remember { mutableStateOf<QuizEngine?>(null) }
    var typed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped on every answer/advance so Compose re-reads the engine's state.
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val season = LocalSeason.current
    val choices = QuizChoices

    val quiz = engine
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        quiz == null -> QuizSetup(
            error = error,
            onStart = {
                loading = true; error = null
                scope.launch {
                    try {
                        // Both sources ask for exactly the span the chips light, ends spelled out explicitly.
                        val set = season.resolvedStudySet
                        val scoped = choices.selection.resolveWithin(set)
                        val book = scoped.book?.name
                        val from = scoped.chapters?.start?.chapter
                        val through = scoped.chapters?.endInclusive?.chapter
                        val source = choices.source
                        val mode = choices.headingMode
                        val pool = when (source) {
                            QuizSource.QUESTIONS ->
                                api.questions(book = book, fromChapter = from, chapter = through)
                                    .filter { choices.round == null || it.roundType == choices.round }
                            QuizSource.HEADINGS -> headingQuestions(
                                api.headings(book = book, fromChapter = from, throughChapter = through),
                                multiBook = !set.isSingleBook,
                                mode = mode,
                            )
                        }
                        val e = QuizEngine(
                            pool,
                            limit = choices.length.count,
                            noneOfTheAbove = source == QuizSource.HEADINGS && mode == HeadingQuizMode.CHOICE_NOTA,
                        )
                        if (e.isEmpty) error = when (source) {
                            QuizSource.QUESTIONS ->
                                "No approved questions match — loosen the filters or contribute some!"
                            QuizSource.HEADINGS -> "No headings available — is the server's ESV service configured?"
                        }
                        else {
                            engine = e
                            typed = source == QuizSource.HEADINGS && mode == HeadingQuizMode.TYPED
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
            onRetry = { engine = quiz.retryMissed() },
            onRestart = { engine = null },
        )

        else -> QuizStepper(quiz, typed, tick, onTick = { tick++ }, onEnd = { engine = null })
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
private fun QuizSetup(error: String?, onStart: () -> Unit) {
    val c = QuizChoices
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .then(keyboardScope(Unit) { e -> if (e.key == Key.Enter) { onStart(); true } else false }),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Quiz me", style = MaterialTheme.typography.titleLarge)
        Text("Source", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuizSource.entries.forEach { s ->
                FilterChip(selected = c.source == s, onClick = { c.source = s }, label = { Text(s.displayName) })
            }
        }
        when (c.source) {
            QuizSource.QUESTIONS -> {
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
            QuizSource.HEADINGS -> {
                Text("Mode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeadingQuizMode.entries.forEach { m ->
                        FilterChip(selected = c.headingMode == m, onClick = { c.headingMode = m }, label = { Text(m.label) })
                    }
                }
            }
        }
        Text("Chapters", style = MaterialTheme.typography.labelLarge)
        ChapterChips(selected = c.selection, range = true, onSelect = { c.selection = it })
        Text("Questions", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuizLength.entries.forEach { n ->
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
private fun QuizStepper(quiz: QuizEngine, typed: Boolean, tick: Int, onTick: () -> Unit, onEnd: () -> Unit) {
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
        quiz.items.mapNotNull { it.question.bookCode?.let(::bookByCode) }.distinct().sortedBy { it.ordinal }
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
        val ok = typedAnswerCorrect(item.question, b, chapter, multiBook = !set.isSingleBook)
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

    // A right answer flashes ✓ and moves on by itself; leaving the screen cancels the wait.
    if (verdict == true) LaunchedEffect(item) { delay(CORRECT_FLASH_MS); advance() }

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
                Text(item.question.roundType.displayName, style = MaterialTheme.typography.labelSmall,
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
 * the right answer and waits for Continue.
 */
@Composable
private fun Feedback(item: QuizItem, correct: Boolean, onContinue: () -> Unit) {
    val refs = item.question.references.joinToString("; ")
    if (correct) {
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
    val answer = if (item.noneIsCorrect) "${QuizEngine.NONE_OF_THE_ABOVE} — ${item.question.answer}" else item.question.answer
    Text("Answer: $answer", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    if (refs.isNotEmpty()) Text(refs, style = MaterialTheme.typography.bodySmall)
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
