package net.markdrew.biblebowl.generation.typst

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * Translates basic Markdown (CommonMark + GFM strikethrough, parsed by intellij-markdown) into Typst
 * *markup*, suitable for embedding in a Typst content block `[...]`.
 *
 * Supported: bold, italics, strikethrough, inline HTML `<u>/<b>/<i>/<em>/<strong>` (matched pairs
 * within one inline run) and `<br>`, headings, bullet/numbered lists, block quotes, paragraph and
 * hard line breaks, and code spans/fences. Everything is emitted in Typst *function form*
 * (`#strong[...]`, `#list(...)`) rather than line-start syntax, so the output is position-independent.
 * Unrecognized constructs degrade gracefully to their literal text (Typst-escaped) — never an error.
 */
fun markdownToTypst(markdown: String): String {
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    return MarkdownTypstRenderer(markdown).renderBlocks(tree.children)
}

/**
 * Backslash-escapes Markdown-significant punctuation so [text] survives [markdownToTypst] verbatim.
 * Use when programmatically building Markdown around raw text (e.g. wrapping a word of verse text).
 */
fun markdownEscape(text: String): String = buildString {
    for (c in text) {
        if (c in MARKDOWN_SPECIALS) append('\\')
        append(c)
    }
}

private val MARKDOWN_SPECIALS = "\\`*_{}[]()#+-.!|<>~=&".toSet()

/**
 * Escapes Typst *markup* specials (different from [escapeTypstString]'s string-literal escaping) so
 * arbitrary text renders literally inside a Typst content block. Also disables smart quotes/dashes,
 * matching how the string-literal path renders plain fields.
 */
fun escapeTypstMarkup(s: String): String = buildString {
    for (c in s) {
        if (c in TYPST_MARKUP_SPECIALS) append('\\')
        append(c)
    }
}

private val TYPST_MARKUP_SPECIALS = "\\#[]*_`$@<>~'\"/-+=".toSet()

/** Unescapes Markdown backslash escapes (`\*` → `*`) in leaf-token text before Typst re-escaping. */
private val MARKDOWN_BACKSLASH_ESCAPE = Regex("""\\([!-/:-@\[-`{-~])""")

private val HTML_WRAPPERS = mapOf(
    "u" to "#underline",
    "b" to "#strong",
    "strong" to "#strong",
    "i" to "#emph",
    "em" to "#emph",
)

private val HTML_OPEN_TAG = Regex("""<([a-zA-Z][a-zA-Z0-9]*)\s*>""")
private val HTML_CLOSE_TAG = Regex("""</([a-zA-Z][a-zA-Z0-9]*)\s*>""")
private val HTML_BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

private class MarkdownTypstRenderer(private val source: String) {

    /** Renders sibling block-level nodes, joined by paragraph breaks. */
    fun renderBlocks(nodes: List<ASTNode>): String = nodes
        .filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }
        .map { renderNode(it) }
        .filter { it.isNotBlank() }
        .joinToString("#parbreak()")

    /** Renders sibling inline nodes, wrapping supported matched HTML tag pairs (e.g. `<u>...</u>`). */
    fun renderInline(nodes: List<ASTNode>): String = buildString {
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            val openTag = node.htmlTagName(HTML_OPEN_TAG)?.takeIf { it in HTML_WRAPPERS }
            if (openTag != null) {
                val close = (i + 1 until nodes.size).firstOrNull { j ->
                    nodes[j].htmlTagName(HTML_CLOSE_TAG) == openTag
                }
                if (close != null) {
                    append(HTML_WRAPPERS.getValue(openTag)).append('[')
                    append(renderInline(nodes.subList(i + 1, close)))
                    append(']')
                    i = close + 1
                    continue
                }
            }
            append(renderNode(node))
            i++
        }
    }

    private fun renderNode(node: ASTNode): String = when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> renderInline(node.children)
        MarkdownElementTypes.STRONG ->
            "#strong[${renderInline(node.children.withoutDelimiters())}]"
        MarkdownElementTypes.EMPH ->
            "#emph[${renderInline(node.children.withoutDelimiters())}]"
        GFMElementTypes.STRIKETHROUGH ->
            "#strike[${renderInline(node.children.withoutDelimiters())}]"
        MarkdownElementTypes.CODE_SPAN -> {
            val inner = node.children.filter { it.type != MarkdownTokenTypes.BACKTICK }
                .joinToString("") { it.text() }
            "#raw(\"${escapeTypstString(inner)}\")"
        }
        MarkdownElementTypes.CODE_FENCE -> {
            val content = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
                .joinToString("\n") { it.text() }
            "#raw(block: true, \"${escapeTypstString(content)}\")"
        }
        MarkdownElementTypes.ATX_1 -> heading(node, "1.4em")
        MarkdownElementTypes.ATX_2 -> heading(node, "1.2em")
        MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> heading(node, "1.1em")
        MarkdownElementTypes.SETEXT_1 -> setextHeading(node, "1.4em")
        MarkdownElementTypes.SETEXT_2 -> setextHeading(node, "1.2em")
        MarkdownElementTypes.UNORDERED_LIST -> list(node, "#list")
        MarkdownElementTypes.ORDERED_LIST -> list(node, "#enum")
        MarkdownElementTypes.BLOCK_QUOTE -> "#quote(block: true)[" +
            renderBlocks(node.children.filter { it.type != MarkdownTokenTypes.BLOCK_QUOTE }) + "]"
        MarkdownTokenTypes.EOL -> " "
        MarkdownTokenTypes.HARD_LINE_BREAK -> "#linebreak()"
        MarkdownTokenTypes.HTML_TAG ->
            if (HTML_BR_TAG.matches(node.text())) "#linebreak()" else "" // unpaired tags drop
        else ->
            if (node.children.isNotEmpty()) renderInline(node.children)
            else escapeTypstMarkup(MARKDOWN_BACKSLASH_ESCAPE.replace(node.text(), "$1"))
    }

    private fun heading(node: ASTNode, size: String): String {
        val content = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
            ?: return renderInline(node.children.filter { it.type != MarkdownTokenTypes.ATX_HEADER })
        return "#text(weight: \"bold\", size: $size)[${renderInline(content.children).trim()}]"
    }

    private fun setextHeading(node: ASTNode, size: String): String {
        val content = node.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT }
            ?: return renderInline(node.children)
        return "#text(weight: \"bold\", size: $size)[${renderInline(content.children).trim()}]"
    }

    private fun list(node: ASTNode, function: String): String {
        val items = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .map { item ->
                val body = item.children.filter {
                    it.type != MarkdownTokenTypes.LIST_BULLET && it.type != MarkdownTokenTypes.LIST_NUMBER
                }
                "[${renderBlocks(body)}]"
            }
        return "$function(${items.joinToString(", ")})"
    }

    /** Drops emphasis delimiter tokens (`*`, `_`, `~`) that intellij-markdown keeps as children. */
    private fun List<ASTNode>.withoutDelimiters(): List<ASTNode> = filter {
        it.type != MarkdownTokenTypes.EMPH && it.type != GFMTokenTypes.TILDE
    }

    private fun ASTNode.htmlTagName(pattern: Regex): String? {
        if (type != MarkdownTokenTypes.HTML_TAG) return null
        return pattern.matchEntire(text())?.groupValues?.get(1)?.lowercase()
    }

    private fun ASTNode.text(): String = getTextInNode(source).toString()
}
