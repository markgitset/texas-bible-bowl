package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals

class PdfFileNamesTest {

    @Test
    fun bibleTextDefaultsNameOnlyTheHighlight() {
        assertEquals("bible-text-highlighted.pdf", PdfFileNames.bibleText())
        assertEquals("bible-text.pdf", PdfFileNames.bibleText(highlight = false))
    }

    @Test
    fun bibleTextEncodesEveryOptionInOrder() {
        assertEquals(
            "bible-text-highlighted-2col-justified-page-per-ch-unique-words-14pt.pdf",
            PdfFileNames.bibleText(
                highlight = true,
                twoColumns = true,
                justified = true,
                chapterBreaksPage = true,
                underlineUniqueWords = true,
                fontSize = 14,
            ),
        )
    }

    @Test
    fun bibleTextOmitsTheDefaultFontSize() {
        assertEquals(
            "bible-text-highlighted.pdf",
            PdfFileNames.bibleText(fontSize = PdfFileNames.DEFAULT_FONT_SIZE),
        )
        assertEquals("bible-text-highlighted-8pt.pdf", PdfFileNames.bibleText(fontSize = 8))
    }

    @Test
    fun headingFlashcardsEncodeTheThroughChapter() {
        assertEquals("heading-flashcards.pdf", PdfFileNames.headingFlashcards(null))
        assertEquals("heading-flashcards-through-ch5.pdf", PdfFileNames.headingFlashcards(5))
    }

    @Test
    fun indexNamesAreFixed() {
        assertEquals("names-index.pdf", PdfFileNames.namesIndex())
        assertEquals("numbers-index.pdf", PdfFileNames.numbersIndex())
    }
}
