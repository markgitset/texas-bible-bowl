package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.StudyData

/**
 * A complete word index (concordance): every word in [studyData] except common [stopWords] → the verses it
 * occurs in, with per-verse occurrence counts. The display key keeps the first occurrence's casing (so proper
 * nouns read naturally), while grouping and sorting are case-insensitive.
 */
fun fullIndex(studyData: StudyData, stopWords: Set<String> = STOP_WORDS): List<WordIndexEntryC> =
    studyData.wordIndex
        .filterKeys { it !in stopWords }
        .map { (_, ranges) ->
            WordIndexEntryC(
                studyData.excerpt(ranges.first()).excerptText,
                ranges
                    .map { studyData.verseEnclosing(it) ?: error("No verse for range $it") }
                    .groupingBy { it }.eachCount()
                    .map { (verseRef, count) -> WithCount(verseRef, count) },
            )
        }
        .sortedBy { it.key.lowercase() }
