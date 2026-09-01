package net.markdrew.biblebowl.generation.typst

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Round
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TypstEscapeTest {

    @Test
    fun escapesAllStructuralDelimiters() {
        assertEquals("\\\\ \\[ \\] \\# \\* \\_", escapeTypst("\\ [ ] # * _"))
    }

    @Test
    fun backslashEscapedFirstSoEscapesAreNotDoubled() {
        assertEquals("\\\\\\[", escapeTypst("\\["))
    }
}

class PracticeTestTypstTest {

    private fun question(
        prompt: String,
        answer: String,
        choices: List<String> = emptyList(),
        refs: List<String> = emptyList(),
    ) = QuestionDto(
        id = "q-$prompt",
        roundType = Round.FACT_FINDER,
        prompt = prompt,
        answer = answer,
        references = refs,
        choices = choices,
        chapter = 2,
        status = QuestionStatus.APPROVED,
        authorId = "a1",
    )

    @Test
    fun multipleChoiceRoundRendersChoicesAndKeyLetter() {
        val typ = practiceTestTypst(
            Round.FACT_FINDER,
            listOf(question("Who replaced Judas?", "Matthias", choices = listOf("Barsabbas", "Matthias", "Silas"))),
        )
        assertContains(typ, "Fact Finder")
        assertContains(typ, "*A.* Barsabbas")
        assertContains(typ, "*B.* Matthias")
        assertContains(typ, "ANSWER KEY")
        assertContains(typ, "*1.* B. Matthias") // key resolves the correct letter
        assertContains(typ, "Open Bible")
    }

    @Test
    fun questionsAreUnbreakableBlocksFittedToTwoPages() {
        val typ = practiceTestTypst(
            Round.FACT_FINDER,
            listOf(question("Who replaced Judas?", "Matthias", choices = listOf("Barsabbas", "Matthias"))),
        )
        assertContains(typ, "breakable: false", message = "choices must stay on the same page as their question")
        assertContains(typ, "pages_for", message = "the sheet must carry the two-page fit search")
    }

    @Test
    fun shortAnswerRoundRendersBlanksNotChoices() {
        val typ = practiceTestTypst(
            Round.FIND_THE_VERSE,
            listOf(question("\"Repent and be baptized\"", "Acts 2:38", refs = listOf("Acts 2:38"))),
        )
        assertContains(typ, "#box(width: 1.6in, repeat[.])")
        assertFalse(typ.contains("*A.*"), "short-answer rounds must not render choice letters")
        assertContains(typ, "Acts 2:38")
    }

    @Test
    fun answerKeyNamesChapterHeadingsWhenProvided() {
        val typ = practiceTestTypst(
            Round.FACT_FINDER,
            listOf(
                question(
                    "Who replaced Judas?", "Matthias",
                    choices = listOf("Barsabbas", "Matthias"),
                    refs = listOf("Acts 1:26"),
                ),
            ),
            headingsByReference = mapOf("Acts 1:26" to "Matthias Chosen to Replace Judas"),
        )
        assertContains(typ, "(Acts 1:26)")
        assertContains(typ, "Matthias Chosen to Replace Judas")
    }

    @Test
    fun promptsAreEscaped() {
        val typ = practiceTestTypst(
            Round.IDENTIFICATION,
            listOf(question("Who said #blessed* [sic]?", "Peter")),
        )
        assertContains(typ, "\\#blessed\\* \\[sic\\]")
    }

    @Test
    fun closedBibleRoundIsLabeled() {
        val typ = practiceTestTypst(Round.QUOTES, listOf(question("q", "3")))
        assertContains(typ, "Closed Bible")
    }
}
