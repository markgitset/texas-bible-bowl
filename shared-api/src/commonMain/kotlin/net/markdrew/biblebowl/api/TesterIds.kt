package net.markdrew.biblebowl.api

/**
 * Tester IDs and the ZipGrade external-ID scheme (registration backlog item 13, F7), matching the
 * 2026 workbook exactly. Each tester (every contestant — youth and adult) gets:
 *
 *  - a **tester ID**: a small sequential number, assigned per event site in stable, append-only
 *    fashion (nametags print early, so a number never changes once assigned). Each site numbers
 *    from its own base so sites' ranges stay disjoint, like 2026 (Bandina 4–142, White River
 *    204–219): site *i* in [SeasonDto.sites] order starts at `i * TESTER_ID_SITE_BLOCK + 1`.
 *  - an **external ID** `{IndCat}-{CongCode}-{TeamPart}-{testerId}` (e.g. `EI-MR-ENT-4`,
 *    `JI-MR-JEMRLC-COMBO-12`): the contestant's OWN division+experience category, their
 *    congregation's two-letter code, the team part (see [testerTeamPart]), and the tester ID.
 *  - a ZipGrade **class** = the congregation code.
 *
 * ZipGrade (still the primary scan-grading path for 2027, per Mark 2026-07-21) imports these as a
 * per-site CSV: Zip Grade ID, external id, First Name, Last Name, Class.
 */

/** Per-site tester-ID block: site *i* (season order) numbers from `i * block + 1`. */
const val TESTER_ID_SITE_BLOCK = 200

/** The division's single-letter code in workbook category codes (E/J/S/A). */
private val Division.codeLetter: Char
    get() = when (this) {
        Division.ELEMENTARY -> 'E'
        Division.JUNIOR -> 'J'
        Division.SENIOR -> 'S'
        Division.ADULT -> 'A'
    }

/**
 * The workbook's individual-category code for a contestant's own bracket: `EI`/`EE`/`JI`/`JE`/
 * `SI`/`SE` (division initial + Inexperienced/Experienced), or `AD` for adults, whose division
 * has no experience split.
 */
fun indCatCode(division: Division, inexperienced: Boolean): String =
    if (division == Division.ADULT) "AD"
    else "${division.codeLetter}${if (inexperienced) 'I' else 'E'}"

/**
 * The workbook's team-category code for a team's (possibly elevated) bracket: `JI`/`JE`/`SI`/`SE`.
 * Same shape as [indCatCode]; teams never compete Elementary or Adult.
 */
fun teamCatCode(teamDivision: Division, teamInexperienced: Boolean): String =
    indCatCode(teamDivision, teamInexperienced)

/**
 * A team name as it appears inside an external ID: uppercased, spaces to underscores, and any
 * character that isn't a letter, digit, `_`, or `-` dropped (the 2026 names were already in this
 * shape: `AI_AVENGERS`, `MRLC-COMBO`).
 */
fun sanitizeTeamNameForId(name: String): String =
    name.trim().uppercase()
        .map { ch -> if (ch.isWhitespace()) '_' else ch }
        .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        .joinToString("")

/**
 * The team part of an external ID: the team's category code fused with its sanitized name
 * (`SEMCLC-SE`), or a no-teams code for the teamless — `ANT` for adults and `ENT`/`JNT`/`SNT` for
 * unplaced youth (2026 only ever needed `ANT`/`ENT`; the youth codes extend the same pattern).
 */
fun testerTeamPart(
    ownDivision: Division,
    teamName: String?,
    teamDivision: Division?,
    teamInexperienced: Boolean,
): String =
    if (teamName == null || teamDivision == null) "${ownDivision.codeLetter}NT"
    else "${teamCatCode(teamDivision, teamInexperienced)}${sanitizeTeamNameForId(teamName)}"

/**
 * The full ZipGrade external ID, `{IndCat}-{CongCode}-{TeamPart}-{testerId}`. A congregation that
 * hasn't chosen its two-letter code yet shows `??` so the ID stays structurally parseable — fix
 * the code on the congregation and the IDs heal on the next view.
 */
fun externalTesterId(
    division: Division,
    inexperienced: Boolean,
    congregationCode: String,
    teamPart: String,
    testerId: Int,
): String =
    "${indCatCode(division, inexperienced)}-${congregationCode.ifBlank { "??" }}-$teamPart-$testerId"

/** Everything before the last space — ZipGrade wants split names ("Mary Beth Smith" → "Mary Beth"). */
fun zipGradeFirstName(name: String): String =
    name.trim().substringBeforeLast(' ', "").trim()

/** The last space-separated word — the ZipGrade Last Name ("Mary Beth Smith" → "Smith"). */
fun zipGradeLastName(name: String): String =
    name.trim().substringAfterLast(' ').trim()
