package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.IndexEntry
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.model.VerseRef
import net.markdrew.chupacabra.core.DisjointRangeMap

/** Returns every character range whose word appears exactly once in [studyData] (a "hapax"). */
fun oneTimeWords(studyData: StudyData): List<IntRange> = studyData.wordIndex
    .filterValues { it.size == 1 }.values.flatten()

/**
 * One flashcard's worth of a one-time word: the word as printed, its verse, the heading above it, and
 * the verse's text split around the word (whitespace-normalized) so each renderer can emphasize the
 * word its own way. Cards run in the word's order of appearance.
 */
data class OneTimeWordCard(
    val word: String,
    val verseRef: VerseRef,
    val heading: String?,
    val versePrefix: String,
    val verseSuffix: String,
)

/** The one-time-word flashcard deck of [studyData] — shared by the PDF deck and the Quizlet CSV export. */
fun oneTimeWordCards(studyData: StudyData): List<OneTimeWordCard> {
    val whitespace = Regex("\\s+")
    return oneTimeWords(studyData).sortedBy { it.first }.mapNotNull { range ->
        val verse = studyData.verseEnclosing(range) ?: return@mapNotNull null
        val verseRange = studyData.verseIndex[verse] ?: return@mapNotNull null
        // Locate the word in its verse by raw char offsets (not text search, which could hit a
        // substring of another word), then split the verse around it.
        val raw = studyData.excerpt(verseRange).excerptText
        val start = range.first - verseRange.first
        val end = start + (range.last - range.first + 1)
        OneTimeWordCard(
            word = raw.substring(start, end),
            verseRef = verse,
            heading = studyData.headingCharRanges.valueEnclosing(range),
            versePrefix = raw.substring(0, start).replace(whitespace, " ").trimStart(),
            verseSuffix = raw.substring(end).replace(whitespace, " ").trimEnd(),
        )
    }
}

/** One-time words keyed by word → the single verse each occurs in (source for the alphabetical section). */
fun oneTimeWordsIndexByWord(
    studyData: StudyData,
    ranges: List<IntRange> = oneTimeWords(studyData),
): List<IndexEntry<String, VerseRef>> = ranges.map { range ->
    val ref = studyData.verseEnclosing(range) ?: error("No verse for range $range")
    IndexEntry(studyData.excerpt(range).excerptText, listOf(ref))
}

/** One-time words grouped by verse → the words that occur once in that verse (source for appearance order). */
fun oneTimeWordsIndexByVerse(
    studyData: StudyData,
    ranges: List<IntRange> = oneTimeWords(studyData),
): List<IndexEntry<VerseRef, String>> = ranges
    .groupBy { studyData.verseEnclosing(it) ?: error("No verse for range $it") }
    .map { (ref, wordRanges) -> IndexEntry(ref, wordRanges.map { studyData.excerpt(it).excerptText }) }

/**
 * A word that appears multiple times but only within one section (e.g. one chapter or one heading)
 *
 * @param word the lowercased word
 * @param wordRanges every character range where the word occurs
 * @param section the section that contains all of [wordRanges]
 * @param sectionRange the character range of [section] in [StudyData.text]
 */
data class OneSectionWord<T>(
    val word: String,
    val wordRanges: List<IntRange>,
    val section: T,
    val sectionRange: IntRange
)

/**
 * Builds the list of words that occur more than once but only within a single section
 *
 * "Section" is whatever the keys of [sectionMap] represent — typically [net.markdrew.biblebowl.model.ChapterRef]
 * or heading title strings.
 */
fun <T : Any> oneSectionWords(
    studyData: StudyData,
    sectionMap: DisjointRangeMap<T>,
): List<OneSectionWord<T>> = studyData.wordIndex
    .filterValues { ranges -> ranges.size > 1 } // remove one-time words
    .filterValues { ranges ->
        ranges.map { sectionMap.valueEnclosing(it) }.distinct().count() == 1  // only entries all in same section
    }.map { (word, ranges) ->
        val (sectionRange, section) = sectionMap.entryEnclosing(ranges.first()) ?: throw Exception()
        OneSectionWord(word, ranges, section, sectionRange)
    }
