package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable

/**
 * Texas Bible Bowl competition divisions, by school grade. Grades are derived from birthdates
 * (see [SeasonDto.divisionForBirthdate]) rather than self-reported, so eligibility advances
 * automatically season over season. A contestant competes at the highest division of any team
 * member (never below their own grade/experience level).
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

// ---------------------------------------------------------------------------
// Ages, grades, and divisions from birthdates
// ---------------------------------------------------------------------------

/** Texas school-entry rule: a child must be 5 by the cutoff to enter kindergarten. */
private const val KINDERGARTEN_AGE = 5

/** Parses "yyyy-mm-dd" into (year, month, day), or null when it isn't a plausible date. */
private fun parseIsoDate(iso: String): Triple<Int, Int, Int>? {
    val parts = iso.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (year !in 1900..2200 || month !in 1..12 || day !in 1..31) return null
    return Triple(year, month, day)
}

/** True when [iso] parses as a plausible ISO-8601 birthdate (structure and range; no clock here). */
fun isValidBirthdate(iso: String): Boolean = parseIsoDate(iso) != null

/** Full years of age on [dateIso] for someone born [birthdateIso], or null if either fails to parse. */
fun ageOn(birthdateIso: String, dateIso: String): Int? {
    val (birthYear, birthMonth, birthDay) = parseIsoDate(birthdateIso) ?: return null
    val (year, month, day) = parseIsoDate(dateIso) ?: return null
    val beforeBirthday = month * 100 + day < birthMonth * 100 + birthDay
    return year - birthYear - (if (beforeBirthday) 1 else 0)
}

/**
 * The date this season maps ages to school grades on: the configured [SeasonDto.gradeCutoffDate],
 * or September 1 before the event (the Texas school-entry cutoff) when unset.
 */
val SeasonDto.gradeCutoff: String
    get() = gradeCutoffDate?.takeIf { it.isNotBlank() }
        ?: "${(eventYear.toIntOrNull() ?: 0) - 1}-09-01"

/**
 * The school grade implied by [birthdateIso] this season: age on [gradeCutoff] minus 5, since a
 * typical Texas student turns 5 by the cutoff to enter kindergarten. 13+ means past high school
 * (adult division); null when the birthdate doesn't parse.
 */
fun SeasonDto.gradeForBirthdate(birthdateIso: String): Int? =
    ageOn(birthdateIso, gradeCutoff)?.minus(KINDERGARTEN_AGE)

/**
 * The division implied by [birthdateIso] this season: the grade's division for grades 3–12,
 * [Division.ADULT] past grade 12 (18+/finished high school), and null below grade 3 (too young
 * to compete) or for an unparseable birthdate.
 */
fun SeasonDto.divisionForBirthdate(birthdateIso: String): Division? {
    val grade = gradeForBirthdate(birthdateIso) ?: return null
    return if (grade > 12) Division.ADULT else Division.forGrade(grade)
}

/**
 * A user's division this [season]: [Division.ADULT] for self-attested adults, computed from the
 * birthdate otherwise, and null while the profile has neither (legacy account) or is too young.
 */
fun UserDto.division(season: SeasonDto): Division? = when {
    adult -> Division.ADULT
    birthdate != null -> season.divisionForBirthdate(birthdate)
    else -> null
}

/**
 * A roster entry's own division this [season]: by birthdate, or [Division.ADULT] when none is
 * given (the coach marked them an adult). Null only for legacy entries whose birthdate predates
 * collection — server validation keeps new youth entries in a real division.
 */
fun RosterEntryDto.division(season: SeasonDto): Division? =
    birthdate?.let { season.divisionForBirthdate(it) } ?: Division.ADULT

/**
 * The division a team competes in — that of its highest member (declaration order of [Division]
 * ascends ELEMENTARY → ADULT). Null for an empty roster.
 */
fun TeamDto.division(season: SeasonDto): Division? =
    members.mapNotNull { it.division(season) }.maxOrNull()

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
