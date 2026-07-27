package net.markdrew.biblebowl.model

/**
 * One question from a multiple-choice study guide.
 *
 * @param chapterRef the chapter the question is about
 * @param questionNum the 1-based question number within that chapter
 * @param question the question text
 * @param verseRefString the reference (as printed in the study guide) for the answer source
 * @param correctAnswer 0-based index into [choices] of the correct option
 * @param choices the answer choices, in display order (usually four, occasionally three or five)
 */
data class StudyGuideQuestion(
    val chapterRef: ChapterRef,
    val questionNum: Int,
    val question: String,
    val verseRefString: String,
    val correctAnswer: Int,
    val choices: List<String>,
)

/**
 * Loads [StudyGuideQuestion]s from a per-study-set `study-guide.tsv` classpath resource. Ported from
 * bible-bowl; the TSV is the app's own curated content (not ESV text), so it ships in the jar.
 */
object StudyGuideParser {

    private fun resourcePath(studySet: StudySet): String = "/${studySet.simpleName}/study-guide.tsv"

    /**
     * Parses one TSV line into a [StudyGuideQuestion]. The first six columns are book, chapter, questionNum,
     * question, verseRef, and the answer letter; every remaining non-blank column is an answer choice (so
     * three, four, or five choices are all accepted, as is a trailing blank column from a stray tab). Every
     * field is trimmed, so a carriage return left by a CRLF line ending split only on `\n` is tolerated.
     *
     * @throws IllegalArgumentException if the book can't be parsed or the answer letter has no matching choice
     */
    fun parseTsvLine(split: List<String>): StudyGuideQuestion {
        require(split.size >= 6) { "Missing fields in: $split" }
        val book = Book.parse(split[0].trim()) ?: throw IllegalArgumentException("Could not parse book '${split[0]}'")
        val chapterRef = book.chapterRef(split[1].trim().toInt())
        val questionNum = split[2].trim().toInt()
        val question = split[3].trim()
        val verseRef = split[4].trim()
        val answerLetter = split[5].trim()
        require(answerLetter.isNotEmpty()) { "Missing answer letter in: $split" }
        val correctAnswer = answerLetter.lowercase()[0] - 'a'
        val choices = split.drop(6).map { it.trim() }.filter { it.isNotEmpty() }
        require(correctAnswer in choices.indices) { "Correct answer, $answerLetter, has no matching choice in: $split" }
        return StudyGuideQuestion(chapterRef, questionNum, question, verseRef, correctAnswer, choices)
    }

    /** The raw study-guide TSV bytes for [studySet], or null if the season bundles no study guide. */
    fun rawTsvOrNull(studySet: StudySet): ByteArray? =
        StudyGuideParser::class.java.getResourceAsStream(resourcePath(studySet))?.use { it.readBytes() }

    /** The parsed study guide for [studySet], or null if the season bundles no `study-guide.tsv`. */
    fun loadStudyGuideOrNull(studySet: StudySet): List<StudyGuideQuestion>? {
        val bytes = rawTsvOrNull(studySet) ?: return null
        return bytes.decodeToString()
            .split('\n')
            .drop(1) // header row
            .filter { it.isNotBlank() }
            .map { parseTsvLine(it.split('\t')) }
    }
}
