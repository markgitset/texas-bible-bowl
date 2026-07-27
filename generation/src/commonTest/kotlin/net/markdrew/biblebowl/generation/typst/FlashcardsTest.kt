package net.markdrew.biblebowl.generation.typst

import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.model.Round
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FlashcardsTest {

    private fun question(prompt: String, answer: String, refs: List<String> = emptyList()) = QuestionDto(
        id = "q", roundType = Round.IDENTIFICATION, prompt = prompt, answer = answer,
        references = refs, chapter = 2, status = QuestionStatus.APPROVED, authorId = "a",
    )

    @Test
    fun questionsMapToNumberedCards() {
        val cards = listOf(question("Who?", "Peter", listOf("Acts 2:14")), question("Where?", "Jerusalem")).toFlashcards()
        assertEquals(2, cards.size)
        assertEquals(CardText.Plain("Who?"), cards[0].front)
        assertEquals(CardText.Plain("Peter"), cards[0].back)
        assertEquals(CardText.Plain("Acts 2:14"), cards[0].note)
        assertEquals("1 of 2", cards[0].footer)
        assertEquals("2 of 2", cards[1].footer)
    }

    @Test
    fun typstStringEscaping() {
        assertEquals("he said \\\"hi\\\" \\\\ bye", escapeTypstString("he said \"hi\" \\ bye"))
    }

    @Test
    fun deckContainsCardsAndDuplexLayout() {
        val typ = flashcardsTypst(listOf(question("Say \"Repent\"?", "Peter")).toFlashcards())
        assertContains(typ, """question: "Say \"Repent\"?"""")
        assertContains(typ, "cards_per_page")
        // Mirrored x for backs is the duplex-alignment trick from the original generator.
        assertContains(typ, "(columns - col - 1) * card_width")
    }

    @Test
    fun plainFieldsStillEmitQuotedStrings() {
        // Byte-compat guard for the question and heading decks, which use the String constructor.
        val typ = flashcardsTypst(listOf(Flashcard(front = "Word", back = "Acts 2:14", note = "context")))
        assertContains(typ, """(question: "Word", answer: "Acts 2:14", note: "context", footer: ""),""")
    }

    @Test
    fun markdownNoteEmitsContentBlock() {
        val card = Flashcard(
            front = CardText.Plain("gopher"),
            back = CardText.Plain("Genesis 6:14"),
            note = CardText.Markdown("Make yourself an ark of **<u>gopher</u>** wood."),
            footer = "1 of 1",
        )
        val typ = flashcardsTypst(listOf(card))
        assertContains(typ, "note: [Make yourself an ark of #strong[#underline[gopher]] wood.]")
        assertContains(typ, """question: "gopher"""")
    }
}
