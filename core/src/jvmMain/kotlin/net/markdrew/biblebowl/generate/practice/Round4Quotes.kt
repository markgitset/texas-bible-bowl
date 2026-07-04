package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.biblebowl.model.identifyDoubleQuotes
import net.markdrew.biblebowl.model.identifySingleQuotes
import net.markdrew.biblebowl.model.trim
import net.markdrew.chupacabra.core.DisjointRangeMap
import net.markdrew.chupacabra.core.length

/**
 * Renders a Round 4 ("In What Chapter — Quotes") test sheet from the study text and returns the complete
 * Typst source, or null if [practiceTest]'s content covers fewer than four chapters.
 *
 * Mines the study text for unambiguous in-quotes sentences and asks contestants to identify the chapter each
 * quotation comes from. Ported from bible-bowl, rewritten to return a string instead of writing a file.
 */
fun quotesTypst(practiceTest: PracticeTest): String? {
    val studyData: StudyData = practiceTest.content.studyData

    // not enough chapters in practice content to build a reasonable practice test
    if (practiceTest.content.coveredChapters.size < 4) return null

    val filteredCluePool: Map<IntRange, ChapterRef> = round4CluePool(practiceTest)

    val quotesToFind: List<MultiChoiceQuestion> = filteredCluePool.entries
        .shuffled(practiceTest.random).take(practiceTest.numQuestions)
        .map { (range, chapter) ->
            Question(
                """“${studyData.text.substring(range)}”""",
                listOf(chapter),
                studyData.verseContaining(range.first)?.let { listOf(it) },
            )
        }.map { multiChoice(it, practiceTest.content.coveredChapters, practiceTest.random) }

    return inWhatChapterTypst(practiceTest, quotesToFind)
}

private fun round4CluePool(practiceTest: PracticeTest): Map<IntRange, ChapterRef> {
    val studyData = practiceTest.content.studyData
    val chapters = practiceTest.content.coveredChapters
    var cluePool: DisjointRangeMap<ChapterRef> = DisjointRangeMap(
        studyData.chapters
            .maskedBy(identifySingleQuotes(studyData.text).gcdAlignment(identifyDoubleQuotes(studyData.text)))
            .maskedBy(studyData.sentences)
            .mapKeys { (range, _) ->
                trim(studyData.text, range) { c -> c in " :,‘’“”\n" }
            }.filterNot { (range, _) -> range.isEmpty() }
    )
    if (!practiceTest.content.allChapters) {
        val lastIncludedOffset: Int = studyData.chapterIndex[chapters.last()]?.last ?: throw Exception()
        cluePool = cluePool.enclosedBy(0..lastIncludedOffset)
    }
    val longEnoughClues = cluePool.filterKeys { it.length() >= 15 }
    // finally, ensure that we don't have any clues with more than one correct answer
    return longEnoughClues.entries
        .groupBy { (k, _) -> studyData.text.substring(k).lowercase() } // group clues by the text of the clue
        .values.filter { it.size == 1 }.flatten().associate { (k, v) -> k to v } // only keep groups of one clue
}
