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

/** How an adult prefers to be reached for event communication. */
@Serializable
enum class ContactPreference(val displayName: String) {
    EMAIL("Email"), PHONE("Phone call"), TEXT("Text"),
}

/**
 * Optional contact details for an adult attendee — the 2026 workbook's per-adult columns
 * (address/city/state/zip/phone/email + preferred method). Every field is optional free-form;
 * [email] is only collected where there's no account to get it from (guests — accounts already
 * carry an email).
 */
@Serializable
data class ContactInfoDto(
    val address: String = "",
    val city: String = "",
    val state: String = "",
    val zip: String = "",
    val phone: String = "",
    val email: String = "",
    val preference: ContactPreference? = null,
) {
    fun isEmpty(): Boolean =
        address.isBlank() && city.isBlank() && state.isBlank() && zip.isBlank() &&
            phone.isBlank() && email.isBlank() && preference == null
}

/**
 * Edits the signed-in user's own profile (`PUT /auth/me`); same birthdate/adult rules as signup.
 * [contact] replaces the stored contact info when non-null and is left unchanged when null — so
 * clients that don't collect it yet (the Compose app) can't silently wipe it.
 */
@Serializable
data class UpdateProfileRequest(
    val displayName: String,
    val birthdate: String? = null,
    val adult: Boolean = false,
    val contact: ContactInfoDto? = null,
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
    /** Optional contact details (adults; edited on the account page). Null = never provided. */
    val contact: ContactInfoDto? = null,
    val roles: List<RoleGrant> = emptyList(),
    val permissions: Set<Permission> = emptySet(),
    /**
     * Display names for the congregations the CONGREGATION-scoped grants in [roles] point at,
     * keyed by [RoleGrant.scopeId]. Populated only by the user-management endpoints (so admins see
     * "First Church", not a UUID); empty elsewhere. Lives beside [roles] rather than inside
     * [RoleGrant] because grants are matched by data-class equality.
     */
    val congregationNames: Map<String, String> = emptyMap(),
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
 * One of the season's event locations (2026 ran two: Bandina and White River Youth Camp). [id] is
 * the stable key registrations pin to — assigned on first save as the [siteSlug] of the name (the
 * editors send new sites with a blank id) and unchanged by renames, so an admin can fix a site's
 * name without unpinning every congregation.
 */
@Serializable
data class EventSiteDto(
    val id: String,
    val name: String,
    /** Optional address or location note, shown to coaches picking a site; "" when not provided. */
    val address: String = "",
)

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
    /**
     * Event year, e.g. 2027 (the season label spans two calendar years, e.g. 2026–27). Tolerates
     * the legacy quoted form on read (old payloads / old clients) via [FlexibleIntSerializer].
     */
    @Serializable(with = FlexibleIntSerializer::class)
    val eventYear: Int,
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
    /**
     * Feature toggle: registration features (coach registration, congregations/teams/rosters,
     * claim codes, the registration desk) are live. Off = deployed dark — hidden in the UI and
     * 403 `feature_disabled` on the API for everyone except global admins, who can exercise the
     * feature before launch. Distinct from the registration *window* dates above, which only
     * gate coach mutations once the feature itself is live.
     */
    val registrationEnabled: Boolean = false,
    /** Feature toggle like [registrationEnabled] for scoring: grading desk, standings, score release, My Scores. */
    val gradingEnabled: Boolean = false,
    /**
     * The season's event location(s). Empty or a single site = the frictionless single-site path
     * (nothing to pick); two or more = each congregation's registration must pin to one site
     * (see [RegistrationDto.siteId]) and the event-ops views break down per site.
     */
    val sites: List<EventSiteDto> = emptyList(),
    /**
     * The volunteer positions adult (age-9+) guests may sign up for during registration, in
     * display order — season-configurable in Season settings. Defaults to the 2026 list.
     */
    val volunteerPositions: List<String> = DEFAULT_VOLUNTEER_POSITIONS,
    val tbbScholarshipAmount: String,
    val maryOrbisonAmount: String,
    val paulHendricksonAmount: String,
)

/** The 2026 event's volunteer-position list — the default until a season customizes its own. */
val DEFAULT_VOLUNTEER_POSITIONS: List<String> =
    listOf("Sports Assistant", "Test Monitor", "Test Grader", "Kitchen Helper")

// ---------------------------------------------------------------------------
// Registration (congregations, teams, rosters) — docs/gui-redesign.md §5E
// ---------------------------------------------------------------------------

/** T-shirt sizes collected per roster entry (youth S–L, adult S–3XL). */
@Serializable
enum class ShirtSize(val displayName: String) {
    YS("Youth S"), YM("Youth M"), YL("Youth L"),
    AS("Adult S"), AM("Adult M"), AL("Adult L"), AXL("Adult XL"), AXXL("Adult 2XL"), AXXXL("Adult 3XL"),
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
    /** Optional contact phone number, free-form (e.g. "281-610-8924"); "" when not provided. */
    val phone: String = "",
    /**
     * The congregation's unique two-letter code, e.g. "FB" — a coach picks it once, after which only
     * an admin can change it. Blank until chosen; unique (case-insensitively) across congregations.
     */
    val code: String = "",
)

@Serializable
data class CreateCongregationRequest(
    val name: String,
    val city: String,
    val state: String = "",
    val mailingAddress: String = "",
    val zip: String = "",
    /** Optional contact phone number; blank is allowed. */
    val phone: String = "",
    /**
     * The congregation's unique two-letter code, e.g. "WB" for "West Bexar County Church of Christ".
     * Chosen at creation (a suggestion is derived from the name — see [congregationCodeCandidates]);
     * blank is allowed, and once set only an admin can change it.
     */
    val code: String = "",
)

/** A suggested, currently-available two-letter code for a congregation name (`GET /congregations/code-suggestion`). */
@Serializable
data class CodeSuggestionResponse(val code: String)

/**
 * Edits a congregation's details after creation (`PUT /congregations/{id}`) — allowed to its coach
 * while the registration window is open (admins any time). Name, address, city, state, and ZIP are
 * freely editable. The two-letter [code] is special: a coach may *set* it while it's still blank,
 * but once set only an admin may change it (enforced server-side).
 */
@Serializable
data class UpdateCongregationRequest(
    val name: String,
    val city: String,
    val state: String = "",
    val mailingAddress: String = "",
    val zip: String = "",
    /** Optional contact phone number; blank is allowed. */
    val phone: String = "",
    /** Unique two-letter congregation code; blank leaves it unset. */
    val code: String = "",
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
    /**
     * The member's own congregation — set ONLY when it differs from the surrounding context, i.e.
     * a visiting member on another congregation's (combo) team. Null = belongs to the context's
     * congregation. Visiting members are registered, edited, and billed by their own congregation;
     * only the team slot is borrowed.
     */
    val congregationId: String? = null,
    /** Display name matching [congregationId]; null for a home member. */
    val congregationName: String? = null,
    /**
     * Willing to serve as a tribe leader. Meaningful only for individual (adult) contestants —
     * any adult can lead a tribe, contestant or not; always false on youth roster entries.
     */
    val tribeLeaderWilling: Boolean = false,
)

/**
 * A team, owned by its home congregation's registration. A *combo* team also hosts visiting
 * members from other congregations' registrations (same season) — those members carry their own
 * [RosterEntryDto.congregationId]/[RosterEntryDto.congregationName] and count toward the ≤4 cap
 * and the team's division/experience bracket like anyone else, but stay registered and billed by
 * their own congregation.
 */
@Serializable
data class TeamDto(
    val id: String,
    val name: String,
    val members: List<RosterEntryDto> = emptyList(),
)

@Serializable
data class UpsertTeamRequest(val name: String)

/**
 * (Re)assigns a youth roster entry to [teamId], or frees it to the unassigned pool when null. The
 * target team must have room (≤4) and belong to the same registration — or, for an event-wide
 * REGISTRATION_MANAGE holder (registrar/admin), to another congregation's registration in the same
 * season (a combo team). Used by a coach moving contestants between their own teams / off a team,
 * and by a registrar placing leftover unassigned entries, including across congregations.
 */
@Serializable
data class AssignMemberTeamRequest(val teamId: String? = null)

/**
 * The home registration's view of one of its members placed on another congregation's (combo)
 * team: the entry stays registered/edited/billed here, while the hosting team and congregation are
 * named for display and for the coach's team picker (unassigning pulls the member back home).
 */
@Serializable
data class AwayMemberDto(
    val entry: RosterEntryDto,
    val teamId: String,
    val teamName: String,
    /** The hosting team's congregation (display). */
    val congregationName: String,
)

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
    /** Willing to serve as a tribe leader (any adult can, contestant or not). */
    val tribeLeaderWilling: Boolean = false,
)

/**
 * An attendee's fee bracket by age — the 2026 schedule, which tiered *every* attendee (testers,
 * coaches, guests) purely by age: 9 and up pays the full fee, 3–8 the child fee, and under-3s
 * attend free (with no included t-shirt). Never stored: derived from a birthdate via
 * [ageTierFor], so a returning child's bracket advances on its own season over season.
 */
@Serializable
enum class AgeTier(val displayName: String) {
    AGE_9_PLUS("Age 9+"), AGE_3_TO_8("Age 3–8"), UNDER_3("Under 3"),
}

/**
 * A registered guest — an attendee who is not a contestant (most are volunteers). Guests must
 * register and pay like everyone else, but they are never placed on a team, compete in no
 * division, and get no claim code. The fee bracket derives from [birthdate] (see [ageTierFor]);
 * the age-9+ and 3–8 fees include a t-shirt, hence [shirtSize].
 *
 * Adult (age-9+ tier) guests are the event's volunteer pool: [positions] holds the volunteer
 * positions they signed up for (drawn from [SeasonDto.volunteerPositions], select all that
 * apply) and [tribeLeaderWilling] marks willingness to lead a tribe (feeds tribe assignment).
 * Both are always empty/false for the child tiers.
 */
@Serializable
data class GuestDto(
    val id: String,
    val name: String,
    /** Null for an under-3 guest (no included t-shirt). */
    val shirtSize: ShirtSize? = null,
    /**
     * ISO-8601 birthdate, collected for children (under 9) so their fee tier falls out of their
     * age each season; null = an adult guest (age 9+), whose exact birthdate isn't needed.
     */
    val birthdate: String? = null,
    /** Null only on guests created before gender was collected. */
    val gender: Gender? = null,
    /** Volunteer positions (from [SeasonDto.volunteerPositions]); age-9+ guests only. */
    val positions: List<String> = emptyList(),
    /** Willing to serve as a tribe leader; age-9+ guests only. */
    val tribeLeaderWilling: Boolean = false,
    /** Optional contact details — collected for adult (9+) guests, who have no account. */
    val contact: ContactInfoDto? = null,
)

/**
 * Adds or edits a registered guest (see [GuestDto]). [gender] is required (nullable only for a
 * friendlier server-side error); [shirtSize] is required except for under-3s, who get no shirt;
 * [birthdate] is collected for children (blank/null = adult, age 9+). [positions] must come from
 * the season's volunteer-position list; the server clears positions and [tribeLeaderWilling] for
 * the child tiers. [contact] fully replaces the guest's stored contact info (null or empty clears it).
 */
@Serializable
data class UpsertGuestRequest(
    val name: String,
    val shirtSize: ShirtSize? = null,
    val birthdate: String? = null,
    val gender: Gender? = null,
    val positions: List<String> = emptyList(),
    val tribeLeaderWilling: Boolean = false,
    val contact: ContactInfoDto? = null,
)

/**
 * A durable contestant who competed for this congregation before but has no roster entry yet this
 * season — a returning *candidate*. Team assignments are per-season, so a new event year starts with
 * none; candidates are surfaced (not billed) until the coach enrolls one, which creates that
 * season's roster entry. Identity is [contestantId] (see the `contestants` table).
 */
@Serializable
data class ReturningContestantDto(
    val contestantId: String,
    val name: String,
    /**
     * ISO-8601 birthdate (drives the division this season); null for adults and for
     * workbook-seeded youth, who carry [graduationYear] instead until first enrollment.
     */
    val birthdate: String? = null,
    val gender: Gender? = null,
    /** The most recent season this contestant competed, for display ("last competed 2027"). */
    val lastSeasonYear: String? = null,
    /** Their most recent shirt size, to prefill the enroll form (they may have grown). */
    val lastShirtSize: ShirtSize? = null,
    /** The season this contestant first competed, if known (drives the inexperienced bracket). */
    val firstSeasonYear: String? = null,
    /**
     * The event year this contestant finishes grade 12, derived from a seeded school grade (the
     * 2026 workbook import has grades but no birthdates — item 17, F13). Non-null marks a *seeded
     * youth*: treated as youth despite the null [birthdate], with the real birthdate collected the
     * first time a coach enrolls them (see [EnrollContestantRequest.birthdate]). Stable across
     * seasons where a stored grade would go stale: grade this season = 12 − ([graduationYear] −
     * event year).
     */
    val graduationYear: Int? = null,
)

/** True for a workbook-seeded youth candidate: no birthdate yet, treated as youth by seeded grade. */
val ReturningContestantDto.isSeededYouth: Boolean get() = birthdate == null && graduationYear != null

/**
 * Enrolls a returning [ReturningContestantDto] into the current season — creating that season's
 * roster entry from the durable contestant. [teamId] places them straight on a team (else they land
 * in the unassigned pool); [shirtSize] is re-collected because it changes year to year.
 */
@Serializable
data class EnrollContestantRequest(
    val shirtSize: ShirtSize,
    val teamId: String? = null,
    /**
     * ISO-8601 birthdate, required (server-enforced) when enrolling a seeded youth
     * ([ReturningContestantDto.isSeededYouth]) — the workbook import seeded a grade, not a
     * birthdate, and enrollment is where the real one is finally collected (it must imply a
     * school grade of 3–12, like [UpsertRosterEntryRequest.birthdate]). Ignored otherwise.
     */
    val birthdate: String? = null,
)

/**
 * Registrar-only: attaches an existing person (by [personId], found via `GET /admin/people`) to a
 * congregation's current-season registration as a contestant — the cross-congregation reuse path
 * for a person who moved congregations (`POST /admin/registration/{congregationId}/attach-person`).
 * Unlike a coach's enroll, the person need not have prior history at the congregation; the
 * one-participation-per-season rule still holds, so a person already registered this season is
 * refused. [shirtSize]/[teamId]/[birthdate] behave as in [EnrollContestantRequest].
 */
@Serializable
data class AttachPersonRequest(
    val personId: String,
    val shirtSize: ShirtSize,
    val teamId: String? = null,
    val birthdate: String? = null,
)

/** A congregation's registration for one season; unique per (congregation, seasonYear). */
@Serializable
data class RegistrationDto(
    val id: String,
    val congregation: CongregationDto,
    val seasonYear: String,
    val status: RegistrationStatus,
    /**
     * The [EventSiteDto.id] of the event site this congregation attends. Null while unchosen —
     * which is the permanent (and fine) state for a single-site season, where [SeasonDto.siteFor]
     * resolves the lone site regardless; a multi-site season requires a choice before submit.
     */
    val siteId: String? = null,
    val teams: List<TeamDto> = emptyList(),
    /** Individual (adult) contestants — never on a team, each competes in the Adult division. */
    val individuals: List<RosterEntryDto> = emptyList(),
    /**
     * Eligible youth contestants (grades 3–12) not currently on any team. They compete
     * individually in their own division and experience bracket — the normal home for elementary
     * contestants, since there are no Elementary teams (one may still play up onto a Junior/Senior
     * team). A coach can assign these to a team, a registration may still be submitted with some
     * here, and a registrar places leftover Junior/Senior entries before the event. Counted as
     * contestants for fees.
     */
    val unassigned: List<RosterEntryDto> = emptyList(),
    /**
     * This registration's members placed on other congregations' (combo) teams. Still registered,
     * edited, and billed here — they appear in the hosting registration's [teams] as visiting
     * members, so they are deliberately NOT in [teams]/[unassigned] above (see [AwayMemberDto]).
     */
    val awayMembers: List<AwayMemberDto> = emptyList(),
    /** Registered guests (mostly volunteers) — they pay too, but aren't contestants (see [GuestDto]). */
    val guests: List<GuestDto> = emptyList(),
    /** Computed total in cents (contestants + guests), or null while a needed fee is TBD. */
    val totalCents: Int? = null,
    /** ISO-8601 instant of the last submit, or null while a draft. */
    val submittedAt: String? = null,
    /** ISO-8601 instant when payment was marked received at the registration desk, or null. */
    val paidAt: String? = null,
)

/**
 * Pins a congregation's registration to one of the season's event sites
 * (`PUT /registration/{congregationId}/site`); [siteId] must be a current [SeasonDto.sites] id.
 */
@Serializable
data class SetRegistrationSiteRequest(val siteId: String)

/** Everything the register screen needs to resume: who I coach, my current-season registration, window state. */
@Serializable
data class MyRegistrationResponse(
    val congregations: List<CongregationDto> = emptyList(),
    val registration: RegistrationDto? = null,
    val windowOpen: Boolean = false,
    /**
     * Contestants who competed for this congregation before but aren't on this season's roster yet —
     * offered for one-click enrollment. Empty until the coach's first congregation has prior-year
     * contestants who are still youth-eligible this season.
     */
    val returningCandidates: List<ReturningContestantDto> = emptyList(),
)

/**
 * Response of every registration mutation: the updated registration plus the recomputed
 * returning-candidate list. Carrying the candidates on each response keeps clients in sync in one
 * round trip — enrolling consumes a candidate, and removing a prior-season contestant makes them
 * a candidate again (the durable contestant survives their roster entry).
 */
@Serializable
data class RegistrationUpdateResponse(
    val registration: RegistrationDto,
    val returningCandidates: List<ReturningContestantDto> = emptyList(),
)

/** Minimal coach contact for the registration desk — deliberately not a full [UserDto]. */
@Serializable
data class CoachContactDto(
    val displayName: String,
    val email: String,
    /** The coach's account contact details (item 9, F3), when they've provided any. */
    val contact: ContactInfoDto? = null,
)

/** One registration-desk row: every congregation appears; [registration] is null when none started this season. */
@Serializable
data class RegistrationDeskRowDto(
    val congregation: CongregationDto,
    val registration: RegistrationDto? = null,
    val coaches: List<CoachContactDto> = emptyList(),
    /** Prior-year contestants (youth or adult) still eligible but not on this season's roster — a registrar may enroll them. */
    val returningCandidates: List<ReturningContestantDto> = emptyList(),
)

/**
 * The full registration desk (`GET /admin/registrations`), for the current season by default or a
 * past one via `?year=`. [seasonYear] is the year shown; [availableYears] is every year with any
 * registration data (plus the current one), newest first, for the desk's year picker.
 */
@Serializable
data class RegistrationDeskResponse(
    val seasonYear: String,
    val rows: List<RegistrationDeskRowDto> = emptyList(),
    val availableYears: List<String> = emptyList(),
)

/** Marks a registration's payment received (true) or clears it (false). */
@Serializable
data class SetPaidRequest(val paid: Boolean)

/**
 * One tester (= contestant) with their assigned IDs (registration backlog item 13, F7; see
 * shared-api TesterIds.kt for the scheme). Current servers always number every tester (one
 * season-wide sequence, site-agnostic), so [testerId] is only null on the wire for old data.
 */
@Serializable
data class TesterRowDto(
    val rosterEntryId: String,
    /** Stable season-wide sequential ID (never reassigned once given). */
    val testerId: Int? = null,
    /** ZipGrade external ID, e.g. "EI-MR-ENT-4"; null when the division is unknown. */
    val externalId: String? = null,
    val name: String,
    val congregationName: String,
    /** The congregation's two-letter code — the ZipGrade "class"; "" while unchosen. */
    val congregationCode: String = "",
    /** Hosting team (a visiting combo member shows their host team); null for the teamless. */
    val teamName: String? = null,
    /** The contestant's own division; null only for an unparseable legacy birthdate. */
    val division: Division? = null,
    val inexperienced: Boolean = false,
    /** The resolved event site; null while the registration is unpinned in a multi-site season. */
    val siteId: String? = null,
    val siteName: String = "",
)

/**
 * Every tester this season with IDs (`GET /admin/testers`), in tester-ID order per site. Fetching
 * the list is what (lazily, append-only) assigns IDs to any not-yet-numbered testers.
 */
@Serializable
data class TesterListResponse(
    val seasonYear: String,
    val rows: List<TesterRowDto> = emptyList(),
)

/**
 * Claims a roster entry by its coach-shared code (`POST /roster/claim`); dashes and case are
 * ignored, so "abcd-2345" matches "ABCD2345". Claiming links the entry to the signed-in account,
 * which is what My Scores' owner scoping keys off.
 */
@Serializable
data class ClaimEntryRequest(val code: String)

// ---------------------------------------------------------------------------
// Person-centric registration API (schema redesign phase 4) — additive; the
// roster-entry DTOs above stay for the current clients until they migrate.
// ---------------------------------------------------------------------------

/** How the signed-in account relates to a person. */
@Serializable
enum class PersonRelation {
    /** This account IS this person (an adult who claimed their own code, or an email match). */
    SELF,
    /** This account MANAGES this person (a parent who claimed a child's code). */
    MANAGED,
}

/**
 * A person — a single human across all seasons (contestant, guest, coach, volunteer). Identity
 * facts live here, independent of any season (per-season facts are [ParticipationDto]). [relation]
 * is how the requesting account relates to this person (null when there is no relation).
 * [claimCode] is included only for people the account may share (its own managed people / a coach's
 * roster) — it's the secret others redeem to claim the person.
 */
@Serializable
data class PersonDto(
    val id: String,
    val name: String,
    /** ISO-8601 birthdate; null for a birthdate-less adult or a grade-seeded youth. */
    val birthdate: String? = null,
    val isAdult: Boolean = false,
    val gender: Gender? = null,
    /** Seeded-youth provenance (the event year they finish grade 12); birthdate wins once set. */
    val graduationYear: Int? = null,
    /** First event year competed, e.g. "2027"; the experience anchor. */
    val firstSeasonYear: String? = null,
    val email: String? = null,
    val contact: ContactInfoDto? = null,
    val claimCode: String? = null,
    val relation: PersonRelation? = null,
)

/**
 * One season's participation of a person — the per-season facets (a person can be several at once:
 * a contestant who also volunteers). Youth/adult, division, and fee tier stay derived from the
 * person's birthdate/adult flag, not stored here.
 */
@Serializable
data class ParticipationDto(
    /** The participant id (what scores, tester ids, and tribe-leader rows reference). */
    val id: String,
    val seasonYear: String,
    val congregationId: String,
    val congregationName: String,
    val isContestant: Boolean = false,
    val isCoach: Boolean = false,
    val teamId: String? = null,
    val teamName: String? = null,
    val shirtSize: ShirtSize? = null,
    val positions: List<String> = emptyList(),
    val tribeLeaderWilling: Boolean = false,
    val testerId: Int? = null,
)

/** A person together with their participations across seasons (newest season first). */
@Serializable
data class PersonWithParticipationsDto(
    val person: PersonDto,
    val participations: List<ParticipationDto> = emptyList(),
)

/**
 * Claims a person by their coach-shared code (`POST /people/claim`); dashes and case are ignored.
 * Whether the account becomes the person ([PersonRelation.SELF]) or a manager of them
 * ([PersonRelation.MANAGED]) is decided automatically: an adult person whose email matches the
 * account is a self-claim, everyone else (a child, or an adult with a different email) is managed.
 */
@Serializable
data class ClaimPersonRequest(val code: String)

/** The result of a successful `POST /people/claim`: the person and the resolved relation. */
@Serializable
data class ClaimPersonResponse(val person: PersonDto, val relation: PersonRelation)

/** `GET /people/mine`: every person the signed-in account is or manages, with their participations. */
@Serializable
data class MyPeopleResponse(val people: List<PersonWithParticipationsDto> = emptyList())

/**
 * `GET /admin/people?query=` (registrar-gated): people whose name matches [query] (blank lists all),
 * with participations — the lookup that feeds the merge-people tool. Capped server-side.
 */
@Serializable
data class PeopleSearchResponse(val people: List<PersonWithParticipationsDto> = emptyList())

/**
 * Merges two people (`POST /admin/people/merge`, registrar-gated) — the mitigation for the global
 * person-matching that makes duplicates likelier. [keepId] survives and absorbs [mergeId]'s
 * participations, claims, and identity gaps; [mergeId] is deleted. Refused (`409`) when the two
 * share a season (they'd double-participate) — the registrar resolves that overlap first.
 */
@Serializable
data class MergePeopleRequest(val keepId: String, val mergeId: String)

/** The surviving person after a merge, with its (now combined) participations. */
@Serializable
data class MergePeopleResponse(val person: PersonWithParticipationsDto)

// ---------------------------------------------------------------------------
// Seed import from the 2026 workbook (item 17, F13) — one-time, idempotent
// ---------------------------------------------------------------------------

/**
 * A youth tester from the workbook: a school grade instead of a birthdate (the workbook never
 * collected birthdates). Seeds a durable contestant whose [ReturningContestantDto.graduationYear]
 * derives from [grade] + the seed season; the real birthdate arrives at first enrollment.
 */
@Serializable
data class SeedMemberDto(
    val name: String,
    val gender: Gender? = null,
    val shirtSize: ShirtSize,
    /** School grade 3–12 in the seed season. */
    val grade: Int,
    /** True when the workbook's Ind Category was an Inexperienced bracket (first year = seed season). */
    val inexperienced: Boolean = false,
    /**
     * The member's OWN congregation when it differs from the team's host — a 2026 combo-team
     * visiting member (e.g. the League City members of MRLC-Combo). Null = belongs to the
     * surrounding [SeedCongregationDto]. Matched by congregation name.
     */
    val congregationName: String? = null,
)

/** An adult individual contestant from the workbook (Ind Category "Adult"). */
@Serializable
data class SeedIndividualDto(
    val name: String,
    val gender: Gender? = null,
    val shirtSize: ShirtSize,
    val tribeLeaderWilling: Boolean = false,
)

/**
 * A guest from the workbook — non-tester attendees, including coach-typed rows (coach *accounts*
 * are not created by the import; see [SeedCongregationDto.coachEmails]). Child guests arrive with
 * a null [birthdate] like adults — the workbook only had age groups, and per the no-fake-birthdates
 * decision none are synthesized.
 */
@Serializable
data class SeedGuestDto(
    val name: String,
    val gender: Gender? = null,
    /** Null for an under-3 guest (no included shirt). */
    val shirtSize: ShirtSize? = null,
    val positions: List<String> = emptyList(),
    val tribeLeaderWilling: Boolean = false,
    val contact: ContactInfoDto? = null,
)

@Serializable
data class SeedTeamDto(val name: String, val members: List<SeedMemberDto> = emptyList())

/**
 * One congregation's complete seed: identity + address, its seed-season registration (teams,
 * team-less youth, adult individuals, guests), and its coaches' emails. [coachEmails] become
 * *pending coach grants*: when someone later signs up with that email, they're granted the
 * congregation-scoped COACH role automatically (no accounts are created by the import).
 */
@Serializable
data class SeedCongregationDto(
    val name: String,
    val city: String = "",
    val state: String = "",
    val mailingAddress: String = "",
    val zip: String = "",
    val phone: String = "",
    /** Two-letter congregation code, e.g. "BH"; blank leaves it unset. */
    val code: String = "",
    /** Site id recorded on the seed-season registration (e.g. "bandina"); null = single site. */
    val siteId: String? = null,
    val coachEmails: List<String> = emptyList(),
    val teams: List<SeedTeamDto> = emptyList(),
    /** Youth testers with no team in the seed season (elementary contestants, mostly). */
    val unassigned: List<SeedMemberDto> = emptyList(),
    val individuals: List<SeedIndividualDto> = emptyList(),
    val guests: List<SeedGuestDto> = emptyList(),
)

/**
 * The full workbook seed (`POST /admin/seed`, global admins only). Idempotent: congregations,
 * contestants, teams, and rows are matched by natural keys (name+city, congregation+name, …), so
 * re-running updates in place instead of duplicating. [seasonYear] is the *historical* season the
 * registrations land in ("2026") — not the current season; its registrations are marked submitted
 * and paid so the history reads as settled.
 */
@Serializable
data class SeedRequest(
    val seasonYear: String,
    val congregations: List<SeedCongregationDto> = emptyList(),
)

/** What `POST /admin/seed` did — counts are totals present after the run, not deltas. */
@Serializable
data class SeedSummary(
    val seasonYear: String,
    val congregations: Int = 0,
    val teams: Int = 0,
    val members: Int = 0,
    val individuals: Int = 0,
    val guests: Int = 0,
    val pendingCoachGrants: Int = 0,
    /** Non-fatal oddities worth a human look (e.g. a division/grade mismatch in the source). */
    val warnings: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// Housing / cabin assignments (item 15, F9) — thin event-ops assignment grid
// ---------------------------------------------------------------------------

/**
 * One cabin (or other sleeping quarters — an RV site, a duplex) at the event, defined per season
 * by the registrar. On a multi-site season each cabin belongs to one site ([siteId]); single-site
 * seasons leave it null, mirroring [RegistrationDto.siteId].
 */
@Serializable
data class CabinDto(
    val id: String,
    val name: String,
    /** The [EventSiteDto.id] this cabin is at; null on single-site seasons (nothing to pick). */
    val siteId: String? = null,
    /** Optional bed count, shown beside the derived occupancy as an eyeball check; null = unknown. */
    val capacity: Int? = null,
    val assignments: List<CabinAssignmentDto> = emptyList(),
)

/**
 * One row of a cabin's assignment grid — free-form, not an optimizer. Either a congregation ×
 * gender *group* (the 2026 pattern: one congregation's boys → one cabin, girls → another;
 * [gender] null = the whole congregation) or an ad-hoc [label] row for families/staff ("Smith
 * family — RV 3"). A group row may also carry a label as a note. Occupant counts are derived
 * client-side from the registration-desk payload, never stored.
 */
@Serializable
data class CabinAssignmentDto(
    val id: String,
    /** The assigned congregation; null for a pure ad-hoc (label-only) row. */
    val congregationId: String? = null,
    /** Display name matching [congregationId]; resolved server-side. */
    val congregationName: String? = null,
    /** Which of the congregation's attendees this row houses; null = all (or an ad-hoc row). */
    val gender: Gender? = null,
    /** Free-form note, or the whole row for an ad-hoc assignment; "" when unused. */
    val label: String = "",
)

/** Adds or renames a cabin. [siteId] must be a current season site id on multi-site seasons. */
@Serializable
data class UpsertCabinRequest(
    val name: String,
    val siteId: String? = null,
    val capacity: Int? = null,
)

/** Adds an assignment row to a cabin: a congregation × gender group and/or a free-text label. */
@Serializable
data class AddCabinAssignmentRequest(
    val congregationId: String? = null,
    val gender: Gender? = null,
    val label: String = "",
)

/**
 * A congregation's cabin check-out duty (replaces the workbook's `Check out assignments` tab):
 * the one adult responsible for their congregation's cabin walk-through at departure. Free-form
 * name — the adult need not be a registered attendee.
 */
@Serializable
data class CheckoutDutyDto(
    val congregationId: String,
    /** Display name matching [congregationId]; resolved server-side. */
    val congregationName: String = "",
    val adultName: String,
)

/** Sets (non-blank) or clears (blank) a congregation's check-out duty adult. */
@Serializable
data class SetCheckoutDutyRequest(val adultName: String)

/**
 * The full housing picture for the current season (`GET /admin/housing`): every cabin with its
 * assignment rows, plus the check-out duty roster (only congregations with a duty set appear —
 * the screen lists registered congregations from the desk payload and fills in from here).
 */
@Serializable
data class HousingResponse(
    val seasonYear: String,
    val cabins: List<CabinDto> = emptyList(),
    val duties: List<CheckoutDutyDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Tribes & tribe leaders (item 16, F10) — thin event-ops assignment tool
// ---------------------------------------------------------------------------

/**
 * One tribe at the event (2026 used color names — "Red", "Green and White Swirl"), defined per
 * season by the registrar with its assigned leaders. On a multi-site season each tribe belongs to
 * one site ([siteId]); single-site seasons leave it null, mirroring [CabinDto.siteId]. The 2026
 * pattern was two leaders per tribe, but that's a convention, not a cap.
 */
@Serializable
data class TribeDto(
    val id: String,
    val name: String,
    /** The [EventSiteDto.id] this tribe is at; null on single-site seasons (nothing to pick). */
    val siteId: String? = null,
    val leaders: List<TribeLeaderDto> = emptyList(),
)

/**
 * One assigned tribe leader — a free-form adult name, like [CheckoutDutyDto.adultName]. The
 * screen's picker is seeded from adults who flagged willingness (item 8: adult guests and
 * individual contestants with `tribeLeaderWilling`), but any adult may be typed in.
 */
@Serializable
data class TribeLeaderDto(val id: String, val name: String)

/** Adds or renames a tribe. [siteId] must be a current season site id on multi-site seasons. */
@Serializable
data class UpsertTribeRequest(
    val name: String,
    val siteId: String? = null,
)

/** Assigns a leader (free-form adult name) to a tribe. */
@Serializable
data class AddTribeLeaderRequest(val name: String)

/** Every tribe for the current season with its leaders (`GET /admin/tribes`). */
@Serializable
data class TribesResponse(
    val seasonYear: String,
    val tribes: List<TribeDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// Scoring (grading desk, release, my scores) — docs/gui-redesign.md §5F
// ---------------------------------------------------------------------------

/**
 * One contestant's row of scores — a grading-grid row for graders, and the same shape a coach or
 * owner sees on My Scores once released. The placement fields are only populated on My Scores
 * (post-release); the grading desk reads full standings from `GET /admin/scores/standings`.
 */
@Serializable
data class ScoreRowDto(
    val rosterEntryId: String,
    val contestantName: String,
    val congregationName: String,
    /** Team name, or null for an individual (adult) contestant. */
    val teamName: String? = null,
    /**
     * The contestant's OWN division (from their birthdate; ADULT for an individual) — individual
     * rounds, scores, and placement always use this, even when their team competes higher.
     */
    val division: Division? = null,
    /** The contestant's own experience bracket (their first season this year). */
    val inexperienced: Boolean = false,
    /** The team's division (its highest member) — only the team round competes here; null off-team. */
    val teamDivision: Division? = null,
    /** The team's experience bracket (a team competes at its most-experienced member's level). */
    val teamInexperienced: Boolean = false,
    /** Entered points keyed by round; rounds not yet graded are absent. */
    val scores: Map<Round, Int> = emptyMap(),
    /** Individual placement in the division bracket (competition ranking: ties share a rank). */
    val rank: Int? = null,
    /** How many individuals compete in the same division bracket. */
    val rankOf: Int? = null,
    /** The team's placement in the division bracket, or null for an individual contestant. */
    val teamRank: Int? = null,
    /** How many teams compete in the same division bracket. */
    val teamRankOf: Int? = null,
    /** The team's total (members' rounds 1–5; the Power Round never counts toward team scores). */
    val teamPoints: Int? = null,
)

/** One line of a division-bracket standings table — an individual contestant or a whole team. */
@Serializable
data class StandingRowDto(
    val rank: Int,
    /** Contestant name, or the team name on a team row. */
    val name: String,
    val congregationName: String,
    /** The contestant's team on an individual row (null = adult individual); null on team rows. */
    val teamName: String? = null,
    /** The roster entry on an individual row; null on team rows. */
    val rosterEntryId: String? = null,
    val points: Int,
    /** The best possible score: the division max for individuals, 200 × member count for teams. */
    val maxPoints: Int,
)

/** Standings for one division bracket, e.g. "Junior (Inexperienced)". */
@Serializable
data class DivisionStandingsDto(
    val division: Division,
    val inexperienced: Boolean = false,
    val individuals: List<StandingRowDto> = emptyList(),
    val teams: List<StandingRowDto> = emptyList(),
)

/**
 * The division tally (`GET /admin/scores/standings`, event-wide SCORE_VIEW_ALL): every division
 * bracket with registered contestants, ranked as grading progresses (ungraded rounds count 0).
 */
@Serializable
data class StandingsResponse(
    val seasonYear: String,
    /** ISO-8601 instant the season's scores were released, or null while unreleased. */
    val releasedAt: String? = null,
    val divisions: List<DivisionStandingsDto> = emptyList(),
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
