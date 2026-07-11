package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.server.export.KahootQuestion
import net.markdrew.biblebowl.server.export.kahootXlsx
import net.markdrew.biblebowl.server.export.quizletTsv
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportsTest {

    @Test
    fun tsvCollapsesStructureCharactersInsideFields() {
        val tsv = quizletTsv(
            listOf(
                "Who\tsaid\n\"Repent\"?" to "Peter",
                "Plain term" to "Plain definition",
            )
        )
        assertEquals(
            listOf("Who said \"Repent\"?\tPeter", "Plain term\tPlain definition"),
            tsv.lines(),
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
