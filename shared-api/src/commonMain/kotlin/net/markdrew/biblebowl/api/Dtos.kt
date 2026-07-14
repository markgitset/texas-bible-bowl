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
    val grade: Int? = null,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

/** Returned on successful auth: the JWT plus the resolved user (with effective permissions). */
@Serializable
data class AuthResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val displayName: String,
    val grade: Int? = null,
    val roles: List<RoleGrant> = emptyList(),
    val permissions: Set<Permission> = emptySet(),
) {
    val division: Division? get() = grade?.let { Division.forGrade(it) }
}

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
 * Prices/dates are display strings on purpose — the site renders values like "TBD (Was $85 in 2026)".
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
    val registrationOpens: String,
    val registrationDeadline: String,
    val scholarshipDeadline: String,
    val priceAdult: String,
    val priceChild: String,
    val priceTshirt: String,
    val tbbScholarshipAmount: String,
    val maryOrbisonAmount: String,
    val paulHendricksonAmount: String,
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
