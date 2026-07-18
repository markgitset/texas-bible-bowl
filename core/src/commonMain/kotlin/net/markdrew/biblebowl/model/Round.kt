package net.markdrew.biblebowl.model

import kotlinx.serialization.Serializable

/** Whether contestants may use a Bible during a round. */
enum class BibleUse {
    CLOSED, OPEN;
    override fun toString(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Texas Bible Bowl competition rounds, with their canonical question counts, time limits,
 * Bible-use rules, grading methods, and point values.
 *
 * @param number 0-5; round 0 is the team Power Round
 * @param shortName slug used in filenames and prose (e.g. "verse-find")
 * @param displayName human-readable round name
 * @param questions standard question count for the round
 * @param minutes standard time limit in minutes
 * @param openBible whether contestants may consult the Bible during the round
 * @param multipleChoice whether answers are multiple-choice (scantron-graded)
 * @param maxPoints the maximum points available in the round
 */
@Serializable
enum class Round(
    val number: Int,
    val shortName: String,
    val displayName: String,
    val questions: Int,
    val minutes: Int,
    val openBible: Boolean,
    val multipleChoice: Boolean,
    val maxPoints: Int,
) {
    FIND_THE_VERSE(
        number = 1,
        shortName = "verse-find",
        displayName = "Find the Verse",
        questions = 40,
        minutes = 25,
        openBible = true,
        multipleChoice = false,
        maxPoints = 40,
    ),
    FACT_FINDER(
        number = 2,
        shortName = "facts",
        displayName = "Fact Finder",
        questions = 40,
        minutes = 20,
        openBible = true,
        multipleChoice = true,
        maxPoints = 40,
    ),
    IDENTIFICATION(
        number = 3,
        shortName = "id",
        displayName = "Identification",
        questions = 40,
        minutes = 20,
        openBible = true,
        multipleChoice = true,
        maxPoints = 40,
    ),
    QUOTES(
        number = 4,
        shortName = "quotes",
        displayName = "Know the Chapter — Quotations",
        questions = 40,
        minutes = 15,
        openBible = false,
        multipleChoice = false,
        maxPoints = 40,
    ),
    EVENTS(
        number = 5,
        shortName = "events",
        displayName = "Know the Chapter — Headings/Events",
        questions = 40,
        minutes = 10,
        openBible = false,
        multipleChoice = false,
        maxPoints = 40,
    ),
    POWER(
        number = 0,
        shortName = "power",
        displayName = "Power Round",
        questions = 50,
        minutes = 25,
        openBible = false,
        multipleChoice = false,
        maxPoints = 50,
    );

    val bibleUse: BibleUse get() = if (openBible) BibleUse.OPEN else BibleUse.CLOSED

    val longName: String get() = when (this) {
        QUOTES -> "In What Chapter - Quotes"
        EVENTS -> "In What Chapter - Events"
        else -> displayName
    }

    /**
     * Whether this round's material is contributed to the community question bank.
     *
     * Only Fact Finder (R2) and Identification (R3) are crowd-sourced. Find the Verse (R1),
     * Know the Chapter — Quotations (R4), and — Headings/Events (R5) are generated deterministically
     * from the ESV text, so they are never submitted or moderated. See [textGenerated].
     */
    val crowdSourced: Boolean get() = this == FACT_FINDER || this == IDENTIFICATION

    /** Whether this round's material is generated from the study text rather than crowd-sourced. */
    val textGenerated: Boolean
        get() = this == FIND_THE_VERSE || this == QUOTES || this == EVENTS

    /** Time limit in minutes for a test of [questions] question(s) at this round's standard pace. */
    fun minutesAtPaceFor(questions: Int): Int = questions * minutes / this.questions

    companion object {
        /** The rounds contestants may contribute to the question bank (R2, R3). */
        val crowdSourcedRounds: List<Round> get() = entries.filter { it.crowdSourced }
    }
}
