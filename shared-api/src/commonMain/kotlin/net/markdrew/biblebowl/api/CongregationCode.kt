package net.markdrew.biblebowl.api

/**
 * Words that don't help identify a congregation and so are skipped when deriving its two-letter
 * code: grammatical filler plus the near-universal "Church of Christ" suffix (encoding the part
 * every congregation shares would waste both letters).
 */
private val CODE_STOP_WORDS = setOf(
    "of", "the", "and", "a", "an", "at", "in", "on", "for", "to", "church", "christ",
)

/**
 * Ranked two-letter code candidates derived from a congregation [name], most mnemonic first, so a
 * caller can pick the first one still available. The idea is that the code should recall the name:
 * "West Bexar County Church of Christ" → "WB", then "WC", "BC" (other significant-word initial
 * pairs), then "WE" (the first word's opening letters), and so on. A full A–Z × A–Z sweep is
 * appended last so an available code always exists.
 *
 * Every candidate is two uppercase letters; the list is de-duplicated in rank order.
 */
fun congregationCodeCandidates(name: String): List<String> {
    val rawWords = name.split(Regex("\\s+"))
        .map { word -> word.filter { it.isLetter() } }
        .filter { it.isNotEmpty() }
    // Drop the filler/denominational words, but never end up with nothing to work from.
    val words = rawWords.filterNot { it.lowercase() in CODE_STOP_WORDS }.ifEmpty { rawWords }

    val candidates = LinkedHashSet<String>()
    fun add(a: Char, b: Char) {
        if (a.isLetter() && b.isLetter()) candidates.add("${a.uppercaseChar()}${b.uppercaseChar()}")
    }

    // 1. Initials of ordered pairs of significant words: (w0,w1), (w0,w2), (w1,w2), …
    for (i in words.indices) for (j in i + 1 until words.size) add(words[i][0], words[j][0])
    // 2. The first two letters of each significant word (WEst, BExar, COunty).
    for (w in words) if (w.length >= 2) add(w[0], w[1])
    // 3. The first word's initial paired with each of its later letters (W+e, W+s, W+t).
    words.firstOrNull()?.let { w -> for (k in 1 until w.length) add(w[0], w[k]) }
    // 4. Each word's initial + the next word's second letter (a softer mnemonic).
    for (i in 0 until words.size - 1) words[i + 1].getOrNull(1)?.let { add(words[i][0], it) }
    // 5. Exhaustive fallback so there's always an available code to fall back on.
    for (a in 'A'..'Z') for (b in 'A'..'Z') candidates.add("$a$b")

    return candidates.toList()
}
