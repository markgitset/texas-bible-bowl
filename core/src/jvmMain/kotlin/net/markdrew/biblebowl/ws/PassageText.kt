package net.markdrew.biblebowl.ws

/** Builds an [IntRange] from the smallest and largest of the first two values in this list. */
fun List<Int>.toIntRange(): IntRange = with(take(2)) { min()..max() }

/**
 * The `passage_meta` block returned by the ESV API for a single passage
 *
 * Each `chapter_*` field is a two-element list containing the first and last absolute verse numbers of that
 * chapter; [toIntRange] collapses those pairs into [IntRange]s. (Ported from bible-bowl minus the
 * Gson/Retrofit wire types — the server maps its own ESV DTOs onto this.)
 */
data class PassageMeta(
    val canonical: String,
    val chapterStart: List<Int>,
    val chapterEnd: List<Int>,
    val prevVerse: Int?,
    val nextVerse: Int?,
    val prevChapter: List<Int>?,
    val nextChapter: List<Int>?,
) {

    /** Verse range of the chapter the passage starts in. */
    fun startsInChapterRange(): IntRange = chapterStart.toIntRange()

    /** Verse range of the chapter the passage ends in. */
    fun endsInChapterRange(): IntRange = chapterEnd.toIntRange()

    /** Verse range of the chapter immediately before this passage, or null if there is none. */
    fun prevChapterRange(): IntRange? = prevChapter?.toIntRange()

    /** Verse range of the chapter immediately after this passage, or null if there is none. */
    fun nextChapterRange(): IntRange? = nextChapter?.toIntRange()
}

/**
 * One passage in the form returned by the ESV API
 *
 * @param canonical canonical reference string (e.g. "Genesis 1:1–3")
 * @param range absolute verse-number range of the passage
 * @param meta the API's `passage_meta` block for this passage
 * @param text the rendered passage text
 */
data class Passage(val canonical: String, val range: IntRange, val meta: PassageMeta, val text: String)
