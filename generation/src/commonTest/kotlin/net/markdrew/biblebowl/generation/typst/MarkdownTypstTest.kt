package net.markdrew.biblebowl.generation.typst

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MarkdownTypstTest {

    @Test
    fun inlineEmphasis() {
        assertEquals("#strong[bold] and #emph[italic]", markdownToTypst("**bold** and *italic*"))
        assertEquals("#strike[gone]", markdownToTypst("~~gone~~"))
    }

    @Test
    fun inlineHtmlPairsWrap() {
        assertEquals("#underline[word]", markdownToTypst("<u>word</u>"))
        assertEquals("#strong[#underline[word]]", markdownToTypst("**<u>word</u>**"))
        assertEquals("a #underline[b #emph[c]] d", markdownToTypst("a <u>b <i>c</i></u> d"))
        assertEquals("a#linebreak()b", markdownToTypst("a<br>b"))
    }

    @Test
    fun unmatchedHtmlTagsDropWithoutBreakingTypst() {
        // An unclosed <u> (or a stray close tag) must never emit an unbalanced bracket.
        assertEquals("before after", markdownToTypst("before <u>after"))
        assertEquals("before after", markdownToTypst("before </u>after"))
        assertEquals("keep text", markdownToTypst("<span>keep text</span>"))
    }

    @Test
    fun headings() {
        assertEquals("#text(weight: \"bold\", size: 1.4em)[Title]", markdownToTypst("# Title"))
        assertEquals("#text(weight: \"bold\", size: 1.2em)[Sub]", markdownToTypst("## Sub"))
        assertEquals("#text(weight: \"bold\", size: 1.1em)[Deep]", markdownToTypst("### Deep"))
    }

    @Test
    fun lists() {
        assertEquals("#list([one], [two])", markdownToTypst("- one\n- two"))
        assertEquals("#enum([first], [second])", markdownToTypst("1. first\n2. second"))
    }

    @Test
    fun listItemsCanCarryInlineFormatting() {
        assertEquals("#list([#strong[one]], [t#underline[w]o])", markdownToTypst("- **one**\n- t<u>w</u>o"))
    }

    @Test
    fun paragraphsAndLineBreaks() {
        assertEquals("one#parbreak()two", markdownToTypst("one\n\ntwo"))
        // A soft wrap inside a paragraph is just a space.
        assertEquals("one two", markdownToTypst("one\ntwo"))
    }

    @Test
    fun codeSpans() {
        assertEquals("#raw(\"x + y\")", markdownToTypst("`x + y`"))
    }

    @Test
    fun typstSpecialsInPlainTextAreEscaped() {
        // Verse-like text with Typst-significant characters must render literally.
        assertEquals(
            """he said, \"Look \#1 \[here\] \${'$'}now\"""",
            markdownToTypst("""he said, "Look #1 \[here\] ${'$'}now""""),
        )
    }

    @Test
    fun markdownEscapeRoundTripsThroughTheRenderer() {
        val raw = "wild *stars*, _scores_, [brackets], <tags>, #hash, ~tilde~ & 2+2=4"
        val typst = markdownToTypst(markdownEscape(raw))
        // Everything survives as literal (Typst-escaped) text: no formatting functions emitted.
        assertEquals(escapeTypstMarkup(raw), typst)
    }

    @Test
    fun unknownConstructsDegradeToText() {
        // Links aren't in the supported subset; they degrade to their literal text.
        val typst = markdownToTypst("see [site](https://example.com)")
        assertContains(typst, "site")
        assertContains(typst, "example.com")
    }
}
