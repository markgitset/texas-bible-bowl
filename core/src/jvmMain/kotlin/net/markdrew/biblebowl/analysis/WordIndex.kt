package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.IndexEntry
import net.markdrew.biblebowl.model.VerseRef

/** Word-index entry: a key with the plain verse references it occurs in. */
typealias WordIndexEntry = IndexEntry<String, VerseRef>

/** Word-index entry whose verse references carry per-verse occurrence counts. */
typealias WordIndexEntryC = IndexEntry<String, WithCount<VerseRef>>

/** Common function words excluded from word/number indices so the index stays about content. */
val STOP_WORDS: Set<String> = setOf(
    "the", "and", "of", "to", "a", "i", "who", "in", "on", "with", "for", "will",
    "you", "was", "is", "his", "he", "from", "that", "they", "are", "their", "it", "be", "like", "were", "have",
    "him", "them", "her", "not", "had", "has", "its", "your", "then", "but", "those", "no", "as", "what", "this",
    "by", "my", "so", "into", "or", "when", "came", "an", "these", "which", "there", "been", "am", "at", "nor", "shall",
    "let", "do", "she", "if", "also", "our", "about", "may", "where", "because", "o", "would", "whose", "here", "how",
    "could", "does", "me", "says", "said", "all", "out", "we", "went", "us",
)
