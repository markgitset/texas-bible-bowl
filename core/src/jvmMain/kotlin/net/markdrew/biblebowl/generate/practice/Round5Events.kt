package net.markdrew.biblebowl.generate.practice

import net.markdrew.biblebowl.model.Heading
import net.markdrew.biblebowl.model.LocationQuestion
import net.markdrew.biblebowl.model.PracticeContent

/**
 * Renders a Round 5 ("In What Chapter — Events") test sheet from the chapter headings and returns the
 * complete Typst source, or null if [practiceTest]'s content covers fewer than three chapters.
 *
 * Uses chapter-heading titles as the questions and asks contestants to identify which chapter each event
 * occurs in. Ported from bible-bowl, rewritten to return a string instead of writing a file.
 */
fun eventsTypst(practiceTest: PracticeTest): String? {
    // not enough chapters in practice content to build a reasonable practice test
    if (practiceTest.content.coveredChapters.size < 3) return null
    return inWhatChapterTypst(practiceTest, headingsCluePool(practiceTest, nChoices = 5))
}

/**
 * Samples chapter headings from [practiceTest]'s content into a list of multiple-choice questions.
 *
 * A heading title that recurs is asked once, with every chapter any heading of that title begins in
 * (anywhere in the study set) accepted as correct.
 */
private fun headingsCluePool(practiceTest: PracticeTest, nChoices: Int): List<MultiChoiceQuestion> {
    val content: PracticeContent = practiceTest.content
    val byTitle: Map<String, List<Heading>> = content.studyData.headings.groupBy { it.title }

    return content.headings().distinctBy { it.title }.shuffled(practiceTest.random).take(practiceTest.numQuestions)
        .map { heading -> LocationQuestion(heading.title, byTitle.getValue(heading.title).map { it.verseRange.start }) }
        .map { multiChoice(it, content.coveredChapters, practiceTest.random, nChoices) }
}
