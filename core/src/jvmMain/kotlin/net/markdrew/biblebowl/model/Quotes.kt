package net.markdrew.biblebowl.model

import net.markdrew.chupacabra.core.DisjointRangeSet

/**
 * Returns the inner ranges of every span delimited by [startChar] on the left and [endPattern] on the right.
 *
 * Each returned range covers the characters between (but excluding) the delimiters. Ported verbatim from
 * bible-bowl; used to mine unambiguous in-quote clues for the Round 4 (Quotations) generator.
 *
 * @throws Exception if a [startChar] is found with no matching [endPattern] occurrence after it.
 */
fun identifyDelimited(text: String, startChar: Char, endPattern: String): DisjointRangeSet {
    var start: Int
    var end = 0
    val endRegex = endPattern.toRegex()
    val quotes = DisjointRangeSet()
    while (true) {
        start = text.indexOf(startChar, end)
        if (start < 0) break
        end = endRegex.find(text, start)?.range?.first
            ?: throw Exception("Unmatched $startChar; no corresponding end pattern ($endPattern) found!")
        quotes.add(start + 1 until end)
    }
    return quotes
}

/** Returns the ranges inside curly single-quotes (‘…’), avoiding the possessive "’s" form. */
fun identifySingleQuotes(text: String): DisjointRangeSet = identifyDelimited(text, '‘', """’(?!s\b)""")

/** Returns the ranges inside curly double-quotes (“…”). */
fun identifyDoubleQuotes(text: String): DisjointRangeSet = identifyDelimited(text, '“', "”")
