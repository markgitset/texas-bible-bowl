package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.LocationQuestion
import net.markdrew.biblebowl.model.groupByWording
import net.markdrew.biblebowl.model.identifyDoubleQuotes
import net.markdrew.biblebowl.model.identifySingleQuotes
import net.markdrew.biblebowl.model.trim
import net.markdrew.chupacabra.core.length

/**
 * Renders a Round 4 ("In What Chapter — Quotes") test sheet from the study text and returns the complete
 * Typst source, or null if [practiceTest]'s content covers fewer than four chapters.
 *
 * Mines the study text for in-quotes sentences and asks contestants to identify the chapter each quotation
 * comes from (any chapter, for a quotation that recurs). Ported from bible-bowl, rewritten to return a string
 * instead of writing a file.
 */
fun quotesTypst(practiceTest: PracticeTest): String? {
    // not enough chapters in practice content to build a reasonable practice test
    if (practiceTest.content.coveredChapters.size < 4) return null

    val quotesToFind: List<MultiChoiceQuestion> = round4CluePool(practiceTest)
        .shuffled(practiceTest.random).take(practiceTest.numQuestions)
        .map { multiChoice(it, practiceTest.content.coveredChapters, practiceTest.random) }

    return inWhatChapterTypst(practiceTest, quotesToFind)
}

/**
 * Every quotation in the covered text worth asking about, one question per wording: a quote that recurs
 * (anywhere in the study set) is asked once, with each verse it occurs in as a correct answer.
 */
private fun round4CluePool(practiceTest: PracticeTest): List<LocationQuestion> {
    val studyData = practiceTest.content.studyData
    val chapters = practiceTest.content.coveredChapters
    val allQuotes: List<IntRange> = studyData.chapters
        .maskedBy(identifySingleQuotes(studyData.text).gcdAlignment(identifyDoubleQuotes(studyData.text)))
        .maskedBy(studyData.sentences)
        .keys
        .map { range -> trim(studyData.text, range) { c -> c in " :,‘’“”\n" } }
        .filterNot { it.isEmpty() || it.length() < 15 }
    val lastIncludedOffset: Int = studyData.chapterIndex[chapters.last()]?.last ?: throw Exception()
    return groupByWording(allQuotes) { studyData.text.substring(it) }
        // asked when any occurrence is in the covered text (cumulative, like the rest of the round)
        .filter { group -> practiceTest.content.allChapters || group.any { it.last <= lastIncludedOffset } }
        .map { group ->
            LocationQuestion(
                """“${studyData.text.substring(group.first())}”""",
                group.mapNotNull { studyData.verseContaining(it.first) }.distinct().sorted(),
            )
        }
}
