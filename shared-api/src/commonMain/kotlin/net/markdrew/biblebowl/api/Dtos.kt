package net.markdrew.biblebowl.api

import kotlinx.serialization.Serializable
import net.markdrew.biblebowl.model.Round

// ---------------------------------------------------------------------------
// Auth & users
// ---------------------------------------------------------------------------

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
    /** ISO-8601 birthdate ("2013-05-04"); required unless [adult]. Drives division eligibility. */
    val birthdate: String? = null,
    /** Self-attested adult (no youth division): no birthdate collected. */
    val adult: Boolean = false,
)

/** Edits the signed-in user's own profile (`PUT /auth/me`); same birthdate/adult rules as signup. */
@Serializable
data class UpdateProfileRequest(
    val displayName: String,
    val birthdate: String? = null,
    val adult: Boolean = false,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

/** Returned on successful auth: the JWT plus the resolved user (with effective permissions). */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

/**
 * [birthdate] and [adult] drive division eligibility (see [division]); accounts created before
 * birthdates were collected may have neither — an incomplete profile with no division.
 */
@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    /** ISO-8601 birthdate for youth; null for adults and legacy accounts. */
    val birthdate: String? = null,
    /** True for self-attested adults (only adults may register a congregation). */
    val adult: Boolean = false,
    val roles: List<RoleGrant> = emptyList(),
    val permissions: Set<Permission> = emptySet(),
)

// ---------------------------------------------------------------------------
// Crowd-sourced questions (MVP focus)
// ---------------------------------------------------------------------------

@Serializable
enum class QuestionStatus { PENDING, APPROVED, REJECTED }

/** A study question contributed by the community, typed to a competition round. */
@Serializable
data class QuestionDto(
    val id: String,
    val roundType: Round,
    val prompt: String,
    val answer: String,
    /** Serialized verse references supporting the answer, e.g. "Acts 2:38" (see core VerseRef). */
    val references: List<String> = emptyList(),
    /** Optional multiple-choice options for [Round.multipleChoice] rounds; the correct one equals [answer]. */
    val choices: List<String> = emptyList(),
    val chapter: Int? = null,
    val status: QuestionStatus = QuestionStatus.PENDING,
    val authorId: String,
    val authorName: String? = null,
    val votes: Int = 0,
    val viewerHasVoted: Boolean = false,
)

@Serializable
data class SubmitQuestionRequest(
    val roundType: Round,
    val prompt: String,
    val answer: String,
    val references: List<String> = emptyList(),
    val choices: List<String> = emptyList(),
    val chapter: Int? = null,
)

/** Admin/grader moderation action on a pending question. */
@Serializable
data class ModerateQuestionRequest(val status: QuestionStatus, val note: String? = null)

// ---------------------------------------------------------------------------
// Bible text
// ---------------------------------------------------------------------------

/** One chapter of Bible text served by the backend's licensed ESV proxy. */
@Serializable
data class ChapterTextDto(
    /** Three-letter book code, e.g. "ACT" (see core Book). */
    val bookCode: String,
    val chapter: Int,
    /** Canonical reference from the ESV API, e.g. "Acts 2". */
    val canonical: String,
    val text: String,
    val translation: String = "ESV",
    /** Required attribution for display alongside the text. */
    val copyright: String = "Scripture quotations are from the ESV® Bible, © 2001 by Crossway. Used by permission.",
)

/** One ESV section heading, for chapter-heading drills and flashcards (Round 5 material). */
@Serializable
data class HeadingDto(
    /** The heading text as it appears in the ESV, e.g. "The Coming of the Holy Spirit". */
    val title: String,
    /** Human-readable verse reference the heading spans, e.g. "2:1-13". */
    val reference: String,
    /** Chapter the heading starts in (1-based, within the season book). */
    val chapter: Int,
    /** 1-based position of this heading within the study set. */
    val index: Int,
    /** Total number of headings in the study set. */
    val total: Int,
)

/** One entry of a study index (e.g. the Numbers index): a key and the verses it occurs in. */
@Serializable
data class IndexEntryDto(
    /** The indexed term as it appears in the text, e.g. "forty" or "1,000". */
    val key: String,
    /** Total occurrences across the study set. */
    val total: Int,
    /** The verses this term occurs in, each with its per-verse occurrence count, in Bible order. */
    val references: List<IndexRefDto>,
)

/** A single verse reference within an [IndexEntryDto], with how many times the term occurs there. */
@Serializable
data class IndexRefDto(
    /** Human-readable verse reference, e.g. "2:41". */
    val reference: String,
    /** Occurrences of the term in this verse. */
    val count: Int,
)

// ---------------------------------------------------------------------------
// Seasons
// ---------------------------------------------------------------------------

/**
 * The season parameters shared by the static site and the app (docs/gui-redesign.md §3): the exact
 * field set of the Hugo site's `[params]`, plus [bookCode]/[chapterCount] for the app's study
 * scoping. Served publicly at `GET /seasons/current`; edited in-app by SEASON_MANAGE holders.
 *
 * Fees are integer cents (`null` = TBD) and registration dates are ISO-8601 (`"2027-02-01"`,
 * `null` = TBD) so registration can compute totals and enforce the window; clients format them
 * for display via [formatCents]/[formatIsoDate]. Scholarship amounts stay display strings.
 */
@Serializable
data class SeasonDto(
    /** Event year, e.g. "2027" (the season label spans two calendar years, e.g. 2026–27). */
    val eventYear: String,
    /** Event dates without the year, e.g. "April 2–4". */
    val eventDateRange: String,
    val eventTheme: String,
    /** The season material as prose, e.g. "Acts" or "Joshua, Judges, and Ruth". */
    val eventScripture: String,
    /**
     * StandardStudySet slug (e.g. "acts", "josh-judg-ruth") — the canonical key for the season's
     * material. Study sets may span several books or partial chapters of multiple books, so
     * season-scoped features should key off this rather than a single book.
     */
    val studySet: String = "acts",
    /** First (often only) book's 3-letter code, e.g. "ACT" — a convenience for single-book uses. */
    val bookCode: String,
    /** Total chapters covered by the study set — derived from [studySet], drives chapter filters. */
    val chapterCount: Int,
    /** Total scholarships awarded in the prior year, e.g. "$25,000". */
    val scholarshipAmount: String,
    /** Day registration opens, ISO-8601 (e.g. "2027-02-01"); null = not yet announced. */
    val registrationOpensOn: String? = null,
    /** Last day to register, ISO-8601, inclusive through end of day America/Chicago; null = TBD. */
    val registrationClosesOn: String? = null,
    /**
     * The date ages are mapped to school grades on, ISO-8601. Null defaults to September 1 before
     * the event — the Texas school-entry cutoff, so a contestant's age on this date implies their
     * grade (see [gradeCutoff]/[divisionForBirthdate] in Domain.kt).
     */
    val gradeCutoffDate: String? = null,
    val scholarshipDeadline: String,
    /** Per-contestant fee in cents, one t-shirt included; null = TBD. */
    val priceContestantCents: Int? = null,
    /** Per-volunteer/adult-attendee fee in cents, t-shirt included; null = TBD. */
    val priceVolunteerCents: Int? = null,
    /** Fee in cents for children ages 3–8 (t-shirt currently included); null = TBD. */
    val priceChildCents: Int? = null,
    /** Extra t-shirt fee in cents; null = TBD. */
    val priceTshirtCents: Int? = null,
    /** True while the season's fees are still subject to change. */
    val feesTentative: Boolean = true,
    val tbbScholarshipAmount: String,
    val maryOrbisonAmount: String,
    val paulHendricksonAmount: String,
)

// ---------------------------------------------------------------------------
// Registration (congregations, teams, rosters) — docs/gui-redesign.md §5E
// ---------------------------------------------------------------------------

/** T-shirt sizes collected per roster entry (youth S–L, adult S–2XL). */
@Serializable
enum class ShirtSize(val displayName: String) {
    YS("Youth S"), YM("Youth M"), YL("Youth L"),
    AS("Adult S"), AM("Adult M"), AL("Adult L"), AXL("Adult XL"), AXXL("Adult 2XL"),
}

/** Contestant gender, collected per roster entry. */
@Serializable
enum class Gender(val displayName: String) { MALE("Male"), FEMALE("Female") }

@Serializable
enum class RegistrationStatus { DRAFT, SUBMITTED }

/** Address fields default to "" for congregations created before they were collected. */
@Serializable
data class CongregationDto(
    val id: String,
    val name: String,
    val city: String,
    /** Two-letter state code, e.g. "TX". */
    val state: String = "",
    /** Street address or PO Box (the city/state/zip complete the mailing label). */
    val mailingAddress: String = "",
    val zip: String = "",
)

@Serializable
data class CreateCongregationRequest(
    val name: String,
    val city: String,
    val state: String = "",
    val mailingAddress: String = "",
    val zip: String = "",
)

/**
 * One contestant — either on a team's roster or registered as an individual (adult).
 * [claimCode] lets a contestant/parent account claim the entry later.
 */
@Serializable
data class RosterEntryDto(
    val id: String,
    val name: String,
    /** ISO-8601 birthdate (drives the division); always null for an individual (adult) contestant. */
    val birthdate: String? = null,
    val shirtSize: ShirtSize,
    /** Null only on entries created before gender was collected. */
    val gender: Gender? = null,
    /**
     * The first event year this contestant competed, e.g. "2027" — equal to the current season for
     * an inexperienced (first-year) contestant (see [isInexperienced]). Null = experienced with an
     * unknown first year. Always null for individuals: the Adult division has no experience split.
     */
    val firstSeasonYear: String? = null,
    val claimCode: String,
    val claimed: Boolean = false,
)

@Serializable
data class TeamDto(
    val id: String,
    val name: String,
    val members: List<RosterEntryDto> = emptyList(),
)

@Serializable
data class UpsertTeamRequest(val name: String)

/**
 * A team roster entry. Adults can't be placed on teams, so [birthdate] must land in grades 3–12.
 * [inexperienced] = this is the contestant's first year competing; the server stores it as the
 * first season year and overrides it from earlier seasons' rosters, so last year's first-year
 * contestant is recognized as experienced this year no matter what the coach checks.
 */
@Serializable
data class UpsertRosterEntryRequest(
    val name: String,
    /** ISO-8601 birthdate; must imply a school grade of 3–12 for the season. */
    val birthdate: String,
    val shirtSize: ShirtSize,
    val gender: Gender,
    val inexperienced: Boolean = false,
)

/**
 * An individual (adult) contestant — adults compete individually, so no birthdate is collected,
 * and no inexperienced flag either (the Adult division has no experience split).
 */
@Serializable
data class UpsertIndividualRequest(
    val name: String,
    val shirtSize: ShirtSize,
    val gender: Gender,
)

/** A congregation's registration for one season; unique per (congregation, seasonYear). */
@Serializable
data class RegistrationDto(
    val id: String,
    val congregation: CongregationDto,
    val seasonYear: String,
    val status: RegistrationStatus,
    val teams: List<TeamDto> = emptyList(),
    /** Individual (adult) contestants — never on a team, each competes in the Adult division. */
    val individuals: List<RosterEntryDto> = emptyList(),
    /** Computed contestant total in cents, or null while fees are TBD. */
    val totalCents: Int? = null,
    /** ISO-8601 instant of the last submit, or null while a draft. */
    val submittedAt: String? = null,
    /** ISO-8601 instant when payment was marked received at the registration desk, or null. */
    val paidAt: String? = null,
)

/** Everything the register screen needs to resume: who I coach, my current-season registration, window state. */
@Serializable
data class MyRegistrationResponse(
    val congregations: List<CongregationDto> = emptyList(),
    val registration: RegistrationDto? = null,
    val windowOpen: Boolean = false,
)

/** Minimal coach contact for the registration desk — deliberately not a full [UserDto]. */
@Serializable
data class CoachContactDto(
    val displayName: String,
    val email: String,
)

/** One registration-desk row: every congregation appears; [registration] is null when none started this season. */
@Serializable
data class RegistrationDeskRowDto(
    val congregation: CongregationDto,
    val registration: RegistrationDto? = null,
    val coaches: List<CoachContactDto> = emptyList(),
)

/** The full registration desk for the current season (`GET /admin/registrations`). */
@Serializable
data class RegistrationDeskResponse(
    val seasonYear: String,
    val rows: List<RegistrationDeskRowDto> = emptyList(),
)

/** Marks a registration's payment received (true) or clears it (false). */
@Serializable
data class SetPaidRequest(val paid: Boolean)

/**
 * Claims a roster entry by its coach-shared code (`POST /roster/claim`); dashes and case are
 * ignored, so "abcd-2345" matches "ABCD2345". Claiming links the entry to the signed-in account,
 * which is what My Scores' owner scoping keys off.
 */
@Serializable
data class ClaimEntryRequest(val code: String)

// ---------------------------------------------------------------------------
// Scoring (grading desk, release, my scores) — docs/gui-redesign.md §5F
// ---------------------------------------------------------------------------

/**
 * One contestant's row of scores — a grading-grid row for graders, and the same shape a coach or
 * owner sees on My Scores once released.
 */
@Serializable
data class ScoreRowDto(
    val rosterEntryId: String,
    val contestantName: String,
    val congregationName: String,
    /** Team name, or null for an individual (adult) contestant. */
    val teamName: String? = null,
    /** The division this contestant competes in: their team's division, or ADULT for an individual. */
    val division: Division? = null,
    /** The team's experience bracket (a team competes at its most-experienced member's level). */
    val inexperienced: Boolean = false,
    /** Entered points keyed by round; rounds not yet graded are absent. */
    val scores: Map<Round, Int> = emptyMap(),
)

/** The grading desk for the current season (`GET /admin/scores`): every contestant, every round. */
@Serializable
data class GradingSheetResponse(
    val seasonYear: String,
    /** ISO-8601 instant the season's scores were released, or null while unreleased. */
    val releasedAt: String? = null,
    val rows: List<ScoreRowDto> = emptyList(),
)

/** One score cell to save; null [points] clears a previously entered score. */
@Serializable
data class ScoreEntryDto(
    val rosterEntryId: String,
    val round: Round,
    val points: Int? = null,
)

/** Batch save from the grading grid — each cell is validated (0..maxPoints, round eligibility). */
@Serializable
data class SaveScoresRequest(val scores: List<ScoreEntryDto>)

/** Releases (true) or retracts (false) the current season's scores. */
@Serializable
data class SetScoresReleasedRequest(val released: Boolean)

/**
 * The signed-in user's visible scores (`GET /scores/mine`): rows for every contestant they own or
 * coach. Empty — with [released] false — until a SCORE_RELEASE holder releases the season's
 * scores; nothing is visible pre-release.
 */
@Serializable
data class MyScoresResponse(
    val seasonYear: String,
    val released: Boolean = false,
    val rows: List<ScoreRowDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Generated-PDF cache administration
// ---------------------------------------------------------------------------

/** Result of `DELETE /generate/cache`: how many cached PDFs were dropped. */
@Serializable
data class ClearPdfCacheResponse(val cleared: Int)

// ---------------------------------------------------------------------------
// Generic API envelope for errors
// ---------------------------------------------------------------------------

@Serializable
data class ApiError(val code: String, val message: String)
