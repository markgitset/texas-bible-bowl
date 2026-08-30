package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.server.export.KahootQuestion
import net.markdrew.biblebowl.server.export.kahootXlsx
import net.markdrew.biblebowl.server.export.quizletTsv
import net.markdrew.biblebowl.server.export.spaceCsv
import net.markdrew.biblebowl.server.export.tsvToCsv
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportsTest {

    @Test
    fun spaceCsvQuotesSpecialsBelowItsHeaderRow() {
        // A field with a comma or quote gets quoted (inner quotes doubled) under the Front,Back
        // header row Space's importer requires.
        val csv = spaceCsv(listOf("Who said \"Repent\"?" to "Peter, the apostle"))
        assertEquals(
            listOf("Front,Back", "\"Who said \"\"Repent\"\"?\",\"Peter, the apostle\""),
            csv.lines(),
        )
    }

    @Test
    fun quizletTsvCollapsesBreaksAndJoinsWithTabs() {
        val txt = quizletTsv(
            listOf(
                // Tabs/newlines in a field would break the tab-and-line-per-card contract, so they
                // collapse; a ";" passes through — nothing splits on it in the default import shape.
                "Who\tsaid\n\"Repent\"?" to "Peter; the apostle",
                "Plain term" to "Plain definition",
            )
        )
        assertEquals(
            listOf("Who said \"Repent\"?\tPeter; the apostle", "Plain term\tPlain definition"),
            txt.lines(),
        )
    }

    @Test
    fun tsvToCsvRequotesFieldsAndDropsTheTrailingEmptyColumn() {
        val tsv = "Book\tChapter\tQuestion\tAnswer A\t\nAct\t1\tTo whom, in Acts, does Luke write?\tTheophilus\t\n"
        assertEquals(
            listOf("Book,Chapter,Question,Answer A", "Act,1,\"To whom, in Acts, does Luke write?\",Theophilus"),
            tsvToCsv(tsv.toByteArray()).decodeToString().lines(),
        )
    }

    @Test
    fun kahootXlsxPartsAreWellFormedXmlWithEscapedContent() {
        val bytes = kahootXlsx(
            listOf(
                KahootQuestion(
                    // Exercise every XML-special character and the length caps.
                    question = "Is <this> & \"that\" 'escaped'? " + "x".repeat(200),
                    answers = listOf("Yes & no", "y".repeat(100), "Maybe", "No"),
                    correctIndices = listOf(1),
                ),
            )
        )
        assertEquals("PK", bytes.decodeToString(0, 2))

        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        var parts = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val content = zip.readBytes()
                parser.parse(ByteArrayInputStream(content)) // throws on malformed XML
                parts++
                if (entry.name == "xl/worksheets/sheet1.xml") {
                    val text = content.decodeToString()
                    assertTrue("Is &lt;this&gt; &amp; &quot;that&quot;" in text, "specials are escaped")
                    assertTrue("x".repeat(121) !in text, "question capped at 120 chars")
                    assertTrue("y".repeat(76) !in text, "answers capped at 75 chars")
                    assertTrue("""<c r="G9"><v>20</v></c>""" in text, "default time limit present")
                    assertTrue("""<c r="H9" t="inlineStr"><is><t xml:space="preserve">1</t></is></c>""" in text)
                }
            }
        }
        assertEquals(5, parts, "all five OPC parts present")
    }
}
