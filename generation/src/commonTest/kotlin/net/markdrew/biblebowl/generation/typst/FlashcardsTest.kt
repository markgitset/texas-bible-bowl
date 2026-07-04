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
        assertEquals("Who?", cards[0].front)
        assertEquals("Peter", cards[0].back)
        assertEquals("Acts 2:14", cards[0].note)
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
}
