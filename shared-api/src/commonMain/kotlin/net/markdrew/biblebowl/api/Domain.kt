package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable

/**
 * Texas Bible Bowl competition divisions, by grade level. A contestant competes at the highest division of
 * any team member (never below their own grade/experience level).
 */
@Serializable
enum class Division(val displayName: String, val gradeRange: IntRange?, val hasPowerRound: Boolean) {
    ELEMENTARY("Elementary", 3..6, hasPowerRound = false),
    JUNIOR("Junior", 7..9, hasPowerRound = true),
    SENIOR("Senior", 10..12, hasPowerRound = true),
    ADULT("Adult", null, hasPowerRound = true);

    companion object {
        /** Returns the division a given school [grade] (3..12) falls into, or null if out of range. */
        fun forGrade(grade: Int): Division? = entries.firstOrNull { it.gradeRange?.contains(grade) == true }
    }
}

/**
 * The rounds of a Texas Bible Bowl written test. Rounds 4 & 5 are closed-Bible (memorization);
 * rounds 1–3 are open-Bible. The Power Round is omitted for [Division.ELEMENTARY].
 *
 * @param displayName human-readable round name
 * @param openBible whether contestants may consult the Bible during the round
 * @param multipleChoice whether answers are multiple-choice (scantron-graded)
 * @param maxPoints the maximum points available in the round
 */
@Serializable
enum class RoundType(
    val displayName: String,
    val openBible: Boolean,
    val multipleChoice: Boolean,
    val maxPoints: Int,
) {
    FIND_THE_VERSE("Find the Verse", openBible = true, multipleChoice = false, maxPoints = 50),
    FACT_FINDER("Fact Finder", openBible = true, multipleChoice = true, maxPoints = 40),
    IDENTIFICATION("Identification", openBible = true, multipleChoice = true, maxPoints = 40),
    KNOW_THE_CHAPTER_QUOTES("Know the Chapter — Quotations", openBible = false, multipleChoice = false, maxPoints = 40),
    KNOW_THE_CHAPTER_HEADINGS("Know the Chapter — Headings/Events", openBible = false, multipleChoice = false, maxPoints = 40),
    POWER("Power Round", openBible = false, multipleChoice = false, maxPoints = 50);
}
