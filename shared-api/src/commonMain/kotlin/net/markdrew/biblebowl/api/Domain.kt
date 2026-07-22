package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable
import net.markdrew.biblebowl.model.Round

/**
 * Texas Bible Bowl competition divisions, by school grade. Grades are derived from birthdates
 * (see [SeasonDto.divisionForBirthdate]) rather than self-reported, so eligibility advances
 * automatically season over season. A team competes at the highest division of any team member,
 * but teams exist only in Junior and Senior: elementary contestants normally compete
 * individually, and one who joins a team plays up (see `TeamDto.division`). Adults are never
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
 * True when a returning contestant with this [birthdate] is offerable as a candidate this season:
 * an adult (no birthdate → enrolls as an individual) or still youth-eligible (grades 3–12). A youth
 * who has aged out of the youth divisions is neither, and isn't offered.
 */
fun SeasonDto.isEligibleReturningCandidate(birthdate: String?): Boolean =
    birthdate == null || divisionForBirthdate(birthdate).let { it != null && it != Division.ADULT }

/**
 * [isEligibleReturningCandidate] for a full candidate, which may be a workbook-seeded youth
 * (grade seeded, no birthdate — [ReturningContestantDto.graduationYear]): such a youth stays
 * eligible through their grade-12 season and ages out after it; seeded grades start at 3, so
 * there is no too-young end. Adults (neither field) are always offerable.
 */
fun SeasonDto.isEligibleReturningCandidate(candidate: ReturningContestantDto): Boolean = when {
    candidate.birthdate != null -> isEligibleReturningCandidate(candidate.birthdate)
    candidate.graduationYear != null -> (eventYear.toIntOrNull() ?: 0) <= candidate.graduationYear
    else -> true
}

/**
 * The school grade this season implied by a seeded [ReturningContestantDto.graduationYear]
 * (12 in the graduation-year season, one less per season before it). May exceed 12 for an
 * aged-out candidate; callers filter with [isEligibleReturningCandidate] first.
 */
fun SeasonDto.gradeForGraduationYear(graduationYear: Int): Int =
    12 - (graduationYear - (eventYear.toIntOrNull() ?: 0))

/** The event year a student in [grade] during [seasonYear] finishes grade 12 (the seed's stable form). */
fun graduationYearFor(seasonYear: String, grade: Int): Int = (seasonYear.toIntOrNull() ?: 0) + (12 - grade)

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
 * A person's division this [season]: [Division.ADULT] when the person is an adult, otherwise from
 * the birthdate, then falling back to the seeded [PersonDto.graduationYear] (a grade-seeded youth
 * with no birthdate yet). Null when none apply or the youth has aged out of the divisions.
 */
fun PersonDto.division(season: SeasonDto): Division? = when {
    isAdult -> Division.ADULT
    birthdate != null -> season.divisionForBirthdate(birthdate)
    graduationYear != null -> season.gradeForGraduationYear(graduationYear).takeIf { it in 3..12 }
        ?.let { Division.forGrade(it) }
    else -> null
}

/**
 * A roster entry's own division this [season]: by birthdate, or [Division.ADULT] for an
 * individual (adult) entry, which carries none. Server validation keeps new team entries in a
 * youth division; null is possible only for an unparseable legacy birthdate.
 */
fun RosterEntryDto.division(season: SeasonDto): Division? =
    birthdate?.let { season.divisionForBirthdate(it) } ?: Division.ADULT

/**
 * The division a team competes in — that of its highest member (declaration order of [Division]
 * ascends ELEMENTARY → SENIOR; adults can't be on teams), but never below [Division.JUNIOR]:
 * there are no Elementary teams, so an elementary member always plays up onto a Junior or Senior
 * team (individually they still test and rank in their own division — see `ScoreRowDto`). Null
 * for an empty roster.
 */
fun TeamDto.division(season: SeasonDto): Division? =
    members.mapNotNull { it.division(season) }.maxOrNull()?.coerceAtLeast(Division.JUNIOR)

/** True when [seasonYear] is this contestant's first — their inexperienced season. */
fun RosterEntryDto.isInexperienced(seasonYear: String): Boolean = firstSeasonYear == seasonYear

/**
 * Each non-adult division splits into experienced and inexperienced brackets, and a team never
 * competes below any member's experience level — so a team is inexperienced only when every
 * member is in their first year. An empty roster is not inexperienced.
 */
fun TeamDto.isInexperienced(seasonYear: String): Boolean =
    members.isNotEmpty() && members.all { it.isInexperienced(seasonYear) }

/** "Junior (Inexperienced)", "Junior", or "Adult" — the Adult division has no experience split. */
fun divisionLabel(division: Division, inexperienced: Boolean): String =
    if (inexperienced && division != Division.ADULT) "${division.displayName} (Inexperienced)"
    else division.displayName

// ---------------------------------------------------------------------------
// Rounds and scoring by division
// ---------------------------------------------------------------------------

/**
 * The rounds contestants in this division take, in test-day order (rounds 1–5, then the Power
 * Round — [Round]'s declaration order). Elementary is exempt from the Power Round.
 */
val Division.rounds: List<Round>
    get() = Round.entries.filter { it != Round.POWER || hasPowerRound }

/**
 * The division's maximum individual score: the sum of its rounds' points — 200 for Elementary
 * (five 40-point rounds), 250 for Junior/Senior/Adult (plus the 50-point Power Round).
 */
val Division.maxScore: Int
    get() = rounds.sumOf { it.maxPoints }

/** The contestant's total across every graded round so far. */
val ScoreRowDto.totalPoints: Int
    get() = scores.values.sum()

/**
 * What one member can contribute to a team score: rounds 1–5 only (200 points). The Power Round
 * never counts toward team totals — that's why the published team maximum is 800 = 4 × 200.
 */
val TEAM_MEMBER_MAX_POINTS: Int = Round.entries.filter { it != Round.POWER }.sumOf { it.maxPoints }

/** A team's total: every member's rounds 1–5 points; Power Round scores are excluded. */
fun teamPoints(memberScores: Iterable<Map<Round, Int>>): Int =
    memberScores.sumOf { scores ->
        scores.entries.sumOf { (round, points) -> if (round == Round.POWER) 0 else points }
    }

/** "1st", "2nd", "3rd", "4th", … "11th", "12th", "13th", "21st", … */
fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/**
 * All contestants in a registration: every HOME team member, every individual (adult) contestant,
 * every unassigned (teamless-but-eligible) youth contestant, and every member away on another
 * congregation's combo team — all are registered and paid for here. Visiting members on this
 * registration's teams (marked by [RosterEntryDto.congregationId]) are excluded: their own
 * congregation counts and bills them.
 */
val RegistrationDto.contestantCount: Int
    get() = teams.sumOf { team -> team.members.count { it.congregationId == null } } +
        individuals.size + unassigned.size + awayMembers.size

/**
 * Every t-shirt this registration orders, one [ShirtSize] per shirt: home team members,
 * individuals, unassigned contestants, and members away on combo teams (all registered and billed
 * here — visiting members on this registration's teams are excluded, exactly like
 * [contestantCount]), plus guests whose fee includes a shirt (under-3 guests get none). Each
 * roster/guest entry is one person, so a coach who also competes appears once, via their single
 * contestant entry.
 */
val RegistrationDto.shirtSizes: List<ShirtSize>
    get() = teams.flatMap { team -> team.members.filter { it.congregationId == null } }.map { it.shirtSize } +
        individuals.map { it.shirtSize } +
        unassigned.map { it.shirtSize } +
        awayMembers.map { it.entry.shirtSize } +
        guests.mapNotNull { it.shirtSize }

// ---------------------------------------------------------------------------
// Age-tiered fees (the 2026 schedule: 9+ full fee, 3–8 child fee, under 3 free)
// ---------------------------------------------------------------------------

/** Fee-tier age boundaries: [ADULT_FEE_MIN_AGE]+ pays the full fee, [CHILD_FEE_MIN_AGE]+ the child fee, younger is free. */
const val CHILD_FEE_MIN_AGE = 3
const val ADULT_FEE_MIN_AGE = 9

/**
 * The fee bracket implied by [birthdate] this season. Ages are computed on the season's
 * [gradeCutoff] — the same date grades are mapped on — so a child's tier and a contestant's grade
 * advance together, one age reference per season. Null (no birthdate collected: adult guests and
 * individual contestants) and unparseable birthdates read as [AgeTier.AGE_9_PLUS].
 */
fun SeasonDto.ageTierFor(birthdate: String?): AgeTier {
    val age = birthdate?.let { ageOn(it, gradeCutoff) } ?: return AgeTier.AGE_9_PLUS
    return when {
        age < CHILD_FEE_MIN_AGE -> AgeTier.UNDER_3
        age < ADULT_FEE_MIN_AGE -> AgeTier.AGE_3_TO_8
        else -> AgeTier.AGE_9_PLUS
    }
}

/**
 * The per-person fee for one attendee in [tier]: under-3s are always free, 3–8 pays the child
 * fee, and 9+ pays the contestant or volunteer fee depending on whether they compete (the two
 * were equal in 2026 but are configured separately). Null = that fee is still TBD.
 */
fun SeasonDto.feeCentsFor(tier: AgeTier, contestant: Boolean): Int? = when (tier) {
    AgeTier.UNDER_3 -> 0
    AgeTier.AGE_3_TO_8 -> priceChildCents
    AgeTier.AGE_9_PLUS -> if (contestant) priceContestantCents else priceVolunteerCents
}

/**
 * One line of a registration's invoice: [count] contestants or guests in [tier], at [eachCents]
 * apiece ([eachCents] null = that fee is TBD, 0 = free). Only tiers actually in use produce lines.
 */
data class FeeLine(val tier: AgeTier, val contestant: Boolean, val count: Int, val eachCents: Int?) {
    val totalCents: Int? get() = eachCents?.times(count)
}

/**
 * The registration's invoice, tiered purely by age like the 2026 `Cost` tab: every contestant this
 * congregation bills (home team members, unassigned, members away on combo teams, and adult
 * individuals) plus every guest, each at their [ageTierFor] bracket's fee. An 8-year-old grade-3
 * contestant pays the child fee, exactly as in 2026.
 */
fun registrationFeeLines(season: SeasonDto, registration: RegistrationDto): List<FeeLine> {
    val contestants = registration.teams.flatMap { team -> team.members.filter { it.congregationId == null } } +
        registration.unassigned + registration.awayMembers.map { it.entry } + registration.individuals
    val contestantTiers = contestants.groupingBy { season.ageTierFor(it.birthdate) }.eachCount()
    val guestTiers = registration.guests.groupingBy { season.ageTierFor(it.birthdate) }.eachCount()
    fun lines(tiers: Map<AgeTier, Int>, contestant: Boolean): List<FeeLine> =
        AgeTier.entries.mapNotNull { tier ->
            tiers[tier]?.let { count -> FeeLine(tier, contestant, count, season.feeCentsFor(tier, contestant)) }
        }
    return lines(contestantTiers, contestant = true) + lines(guestTiers, contestant = false)
}

/**
 * The registration's total in cents: the sum of its [registrationFeeLines] — every attendee
 * (contestants and guests alike) billed by age tier, under-3s free, one t-shirt included for all
 * but under-3s. Only extra t-shirts are still paid at the door/by mail. Null while a fee for a
 * tier actually in use is TBD.
 */
fun registrationTotalCents(season: SeasonDto, registration: RegistrationDto): Int? =
    registrationFeeLines(season, registration).fold(0) { sum: Int?, line ->
        sum?.let { s -> line.totalCents?.plus(s) }
    }

/** Formats a claim code for display/sharing: "ABCD2345" → "ABCD-2345". */
fun formatClaimCode(code: String): String =
    if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code
