package net.markdrew.biblebowl.generate.practice

/** Whether contestants may use a Bible during a round. */
enum class BibleUse {
    CLOSED, OPEN;
    override fun toString(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * The Texas Bible Bowl rounds, with their canonical question counts, time limits, and Bible-use rules.
 *
 * Ported from bible-bowl; carries the print-layout metadata (number, names, timing) the wire-facing
 * [RoundType] deliberately omits. Map from the API enum with [of].
 *
 * @param number 0-5; round 0 is the team Power Round
 * @param shortName slug used in filenames and prose (e.g. "verse-find")
 * @param longName display name as printed on test sheets
 * @param questions standard question count for the round
 * @param minutes standard time limit in minutes
 * @param bibleUse whether contestants may consult their Bible during this round
 */
enum class Round(
    val number: Int,
    val shortName: String,
    val longName: String,
    val questions: Int,
    val minutes: Int,
    val bibleUse: BibleUse,
) {
    POWER(0, "power", "Power Round", 50, 25, BibleUse.CLOSED),
    FIND_THE_VERSE(1, "verse-find", "Find the Verse", 40, 25, BibleUse.OPEN),
    FACT_FINDER(2, "facts", "Fact Finder", 40, 20, BibleUse.OPEN),
    IDENTIFICATION(3, "id", "Identification", 40, 20, BibleUse.OPEN),
    QUOTES(4, "quotes", "In What Chapter - Quotes", 40, 15, BibleUse.CLOSED),
    EVENTS(5, "events", "In What Chapter - Events", 40, 10, BibleUse.CLOSED),
    ;

    /** Time limit in minutes for a test of [questions] question(s) at this round's standard pace. */
    fun minutesAtPaceFor(questions: Int): Int = questions * minutes / this.questions
}
