package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable

/**
 * Texas Bible Bowl competition divisions, by grade level. A team contestant competes at the highest
 * division of any team member (never below their own grade/experience level). Adults are never
 * placed on a team — they compete only as individuals, in [ADULT].
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

/** A roster entry's own division: by grade, or [Division.ADULT] for an individual (adult) entry. */
val RosterEntryDto.division: Division
    get() = grade?.let { Division.forGrade(it) } ?: Division.ADULT

/**
 * The division a team competes in — that of its highest member (declaration order of [Division]
 * ascends ELEMENTARY → SENIOR; adults can't be on teams). Null for an empty roster.
 */
fun TeamDto.division(): Division? = members.maxOfOrNull { it.division }

/** All contestants in a registration: every team member plus every individual (adult) contestant. */
val RegistrationDto.contestantCount: Int
    get() = teams.sumOf { it.members.size } + individuals.size

/**
 * The registration's contestant total in cents ([contestantCount] × the season's contestant fee,
 * one t-shirt each included), or null while the fee is TBD. Volunteers, guests, and extra shirts
 * are paid at the door/by mail and aren't collected in this flow.
 */
fun registrationTotalCents(season: SeasonDto, contestantCount: Int): Int? =
    season.priceContestantCents?.let { it * contestantCount }

/** Formats a claim code for display/sharing: "ABCD2345" → "ABCD-2345". */
fun formatClaimCode(code: String): String =
    if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code
