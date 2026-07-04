package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.StudyData
import net.markdrew.chupacabra.core.DisjointRangeMap

/**
 * Builds a name index from precomputed name [ranges], grouping occurrences by lowercased text and mapping
 * each to the verses it appears in. Ported from bible-bowl. The display key keeps the original casing of the
 * first occurrence (e.g. "Barnabas").
 */
fun buildNamesIndex(studyData: StudyData, ranges: List<IntRange>): List<WordIndexEntry> =
    ranges.map { studyData.excerpt(it) }
        .groupBy { it.excerptText.lowercase() }
        .map { (_, excerpts) ->
            WordIndexEntry(
                excerpts.first().excerptText,
                excerpts.map { studyData.verseEnclosing(it.excerptRange) ?: error("No verse for $it") },
            )
        }

/**
 * The season's names index as count-carrying entries: every proper name (any [WordList] with
 * [WordList.areNames], except [WordList.DIVINE], which is handled separately) → the verses it occurs in,
 * with per-verse occurrence counts. Detection comes from the shared category [resolution] (word lists +
 * curated overrides), so it honors the same per-occurrence corrections as the highlighting.
 */
fun namesIndex(studyData: StudyData, resolution: DisjointRangeMap<String>): List<WordIndexEntryC> {
    val nameRanges: List<IntRange> = resolution.entries
        .filter { (_, token) -> WordList.byToken(token)?.let { it.areNames && it != WordList.DIVINE } == true }
        .map { it.key }
    return buildNamesIndex(studyData, nameRanges)
        .map { entry ->
            WordIndexEntryC(
                entry.key,
                entry.values.groupingBy { it }.eachCount().map { (verseRef, count) -> WithCount(verseRef, count) },
            )
        }
        .sortedBy { it.key.lowercase() }
}
