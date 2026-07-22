package net.markdrew.biblebowl.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AssignMemberTeamRequest
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ClaimEntryRequest
import net.markdrew.biblebowl.api.ClaimPersonRequest
import net.markdrew.biblebowl.api.ClaimPersonResponse
import net.markdrew.biblebowl.api.MergePeopleRequest
import net.markdrew.biblebowl.api.MergePeopleResponse
import net.markdrew.biblebowl.api.MyPeopleResponse
import net.markdrew.biblebowl.api.PeopleSearchResponse
import net.markdrew.biblebowl.api.PersonRelation
import net.markdrew.biblebowl.api.PersonWithParticipationsDto
import net.markdrew.biblebowl.api.ClearPdfCacheResponse
import net.markdrew.biblebowl.api.CodeSuggestionResponse
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.EnrollContestantRequest
import net.markdrew.biblebowl.api.GradingSheetResponse
import net.markdrew.biblebowl.api.HeadingDto
import net.markdrew.biblebowl.api.IndexEntryDto
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.MyScoresResponse
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationUpdateResponse
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.SaveScoresRequest
import net.markdrew.biblebowl.api.ScoreEntryDto
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.AddTribeLeaderRequest
import net.markdrew.biblebowl.api.HousingResponse
import net.markdrew.biblebowl.api.TribesResponse
import net.markdrew.biblebowl.api.UpsertTribeRequest
import net.markdrew.biblebowl.api.SetCheckoutDutyRequest
import net.markdrew.biblebowl.api.UpsertCabinRequest
import net.markdrew.biblebowl.api.SetPaidRequest
import net.markdrew.biblebowl.api.SetRegistrationSiteRequest
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.SetScoresReleasedRequest
import net.markdrew.biblebowl.api.StandingsResponse
import net.markdrew.biblebowl.api.TesterListResponse
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.api.UserDto

/**
 * Platform-provided HTTP client, pre-configured with JSON content negotiation.
 * CIO on JVM/desktop/Android, Js on Wasm; iOS adds its own actual later.
 */
expect fun createHttpClient(): HttpClient

/**
 * Per-request timeout for the shared client. Generous on purpose: the Fly backend scales to zero, so the
 * first request after idle waits out a JVM cold start (+ Postgres connect) that can take ~10-15s.
 */
internal const val BACKEND_REQUEST_TIMEOUT_MS: Long = 30_000L

/**
 * The backend base URL for this platform. Web reads it from a `window.TBB_BACKEND_URL` global (injected
 * into the published page), so the same Wasm bundle runs against localhost in dev and the deployed
 * backend once served from GitHub Pages. Desktop/Android default to localhost for now.
 */
expect fun defaultBaseUrl(): String

/**
 * Thin typed client for the Ktor backend, shared across every platform. Holds the auth token and the
 * signed-in [user] in memory after sign-in so the UI can gate features on [UserDto.permissions].
 */
class TbbApi(val baseUrl: String = defaultBaseUrl()) {
    private val client: HttpClient = createHttpClient()

    var token: String? = null
        private set
    var user: UserDto? = null
        private set

    val isSignedIn: Boolean get() = token != null

    fun signOut() {
        token = null
        user = null
    }

    private fun HttpRequestBuilder.authorize() {
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun remember(auth: AuthResponse): AuthResponse = auth.also {
        token = it.token
        user = it.user
    }

    suspend fun health(): String = client.get("$baseUrl/health").bodyOrThrow()

    /** Fetches the current season parameters (public; the site's params.js reads the same endpoint). */
    suspend fun currentSeason(): SeasonDto = client.get("$baseUrl/seasons/current").bodyOrThrow()

    /** Replaces the current season (requires SEASON_MANAGE; same year edits, new year rolls over). */
    suspend fun updateSeason(season: SeasonDto): SeasonDto =
        client.put("$baseUrl/seasons/current") {
            authorize(); contentType(ContentType.Application.Json); setBody(season)
        }.bodyOrThrow()

    suspend fun register(req: RegisterRequest): AuthResponse =
        remember(
            client.post("$baseUrl/auth/register") {
                contentType(ContentType.Application.Json); setBody(req)
            }.bodyOrThrow()
        )

    suspend fun login(req: LoginRequest): AuthResponse =
        remember(
            client.post("$baseUrl/auth/login") {
                contentType(ContentType.Application.Json); setBody(req)
            }.bodyOrThrow()
        )

    /** Fetches the signed-in user's profile (requires a token). */
    suspend fun me(): UserDto = client.get("$baseUrl/auth/me") { authorize() }.bodyOrThrow()

    /** Updates the signed-in user's profile (display name, birthdate/adult) and refreshes [user]. */
    suspend fun updateProfile(req: UpdateProfileRequest): UserDto =
        client.put("$baseUrl/auth/me") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow<UserDto>().also { user = it }

    /**
     * Re-fetches the signed-in user's profile and updates [user] — needed after a server-side role
     * change (e.g. creating a congregation grants a scoped COACH role) so [UserDto.permissions] and
     * the scoped grants are fresh without a sign-out/in.
     */
    suspend fun refreshUser(): UserDto = me().also { user = it }

    /**
     * Adopts a previously issued [token] (e.g. restored from the browser's localStorage) and rehydrates
     * [user] via `/auth/me`. Returns the user, or null — dropping the token — if the server rejects it
     * as no longer valid. Other failures (offline, cold start) keep the token and rethrow so the caller
     * can decide what a transient error means for it.
     */
    suspend fun restoreSession(token: String): UserDto? {
        this.token = token
        return try {
            me().also { user = it }
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                signOut()
                null
            } else {
                throw e
            }
        }
    }

    /** Lists questions; the server defaults to APPROVED when [status] is null. */
    suspend fun questions(status: QuestionStatus? = null, chapter: Int? = null): List<QuestionDto> =
        client.get("$baseUrl/questions") {
            authorize()
            if (status != null) parameter("status", status.name)
            if (chapter != null) parameter("chapter", chapter)
        }.bodyOrThrow()

    suspend fun submitQuestion(req: SubmitQuestionRequest): QuestionDto =
        client.post("$baseUrl/questions") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun vote(questionId: String): QuestionDto =
        client.post("$baseUrl/questions/$questionId/vote") { authorize() }.bodyOrThrow()

    suspend fun moderate(questionId: String, status: QuestionStatus): QuestionDto =
        client.post("$baseUrl/questions/$questionId/moderate") {
            authorize(); contentType(ContentType.Application.Json); setBody(ModerateQuestionRequest(status))
        }.bodyOrThrow()

    /**
     * Fetches a generated practice-test PDF for [round] (optionally chapter-filtered) as raw bytes.
     * [limit] caps bank-round (R2/R3) tests; [seed] reproduces the same text-round (R1/R4/R5) test.
     */
    suspend fun practiceTestPdf(round: Round, chapter: Int? = null, limit: Int? = null, seed: Int? = null): ByteArray =
        client.get("$baseUrl/generate/practice-test.pdf") {
            authorize()
            parameter("round", round.name)
            if (chapter != null) parameter("chapter", chapter)
            if (limit != null) parameter("limit", limit)
            if (seed != null) parameter("seed", seed)
        }.bodyOrThrow()

    /** Fetches a duplex flashcard deck PDF built from approved questions (filters optional). */
    suspend fun flashcardsPdf(chapter: Int? = null, round: Round? = null): ByteArray =
        client.get("$baseUrl/generate/flashcards.pdf") {
            authorize()
            if (chapter != null) parameter("chapter", chapter)
            if (round != null) parameter("round", round.name)
        }.bodyOrThrow()

    /** Lists the season book's ESV section headings (Round 5 material), optionally through a chapter. */
    suspend fun headings(throughChapter: Int? = null): List<HeadingDto> =
        client.get("$baseUrl/study/headings") {
            authorize()
            if (throughChapter != null) parameter("throughChapter", throughChapter)
        }.bodyOrThrow()

    /** Fetches a chapter-headings flashcard deck PDF, optionally limited through a chapter. */
    suspend fun headingFlashcardsPdf(throughChapter: Int? = null): ByteArray =
        client.get("$baseUrl/generate/heading-flashcards.pdf") {
            authorize()
            if (throughChapter != null) parameter("throughChapter", throughChapter)
        }.bodyOrThrow()

    /**
     * Fetches a formatted PDF of the covered text (verse numbers, headings, poetry, footnotes) with
     * categorized name/number highlighting ([highlight], on by default server-side); set
     * [underlineUniqueWords] to also underline hapax words (those occurring exactly once in the
     * season book) and [chapterBreaksPage] to start each chapter on a new page. Chapter titles render
     * inline with the first verse unless [useHeadingsForChapters] puts them on their own line
     * ([chapterEndLines] adds divider lines beside them); [verseOnNewLine] starts every prose verse
     * on a fresh line.
     */
    suspend fun bibleTextPdf(
        fontSize: Int? = null,
        twoColumns: Boolean = false,
        justified: Boolean = false,
        chapterBreaksPage: Boolean = false,
        useHeadingsForChapters: Boolean = false,
        chapterEndLines: Boolean = false,
        verseOnNewLine: Boolean = false,
        highlight: Boolean = true,
        underlineUniqueWords: Boolean = false,
    ): ByteArray =
        client.get("$baseUrl/generate/bible-text.pdf") {
            authorize()
            if (fontSize != null) parameter("fontSize", fontSize)
            if (twoColumns) parameter("twoColumns", true)
            if (justified) parameter("justified", true)
            if (chapterBreaksPage) parameter("chapterBreaksPage", true)
            if (useHeadingsForChapters) parameter("useHeadingsForChapters", true)
            if (chapterEndLines) parameter("chapterEndLines", true)
            if (verseOnNewLine) parameter("verseOnNewLine", true)
            if (!highlight) parameter("highlight", false)
            if (underlineUniqueWords) parameter("underlineUniqueWords", true)
        }.bodyOrThrow()

    /** Lists the season's numbers index (every numeral/cardinal/ordinal/fraction and the verses it occurs in). */
    suspend fun numbersIndex(): List<IndexEntryDto> =
        client.get("$baseUrl/study/numbers") { authorize() }.bodyOrThrow()

    /** Fetches the numbers-index PDF (alphabetical + by-frequency sections). */
    suspend fun numbersIndexPdf(): ByteArray =
        client.get("$baseUrl/generate/numbers-index.pdf") { authorize() }.bodyOrThrow()

    /** Lists the season's names index (every proper name and the verses it occurs in). */
    suspend fun namesIndex(): List<IndexEntryDto> =
        client.get("$baseUrl/study/names") { authorize() }.bodyOrThrow()

    /** Fetches the names-index PDF (alphabetical + by-frequency sections). */
    suspend fun namesIndexPdf(): ByteArray =
        client.get("$baseUrl/generate/names-index.pdf") { authorize() }.bodyOrThrow()

    /**
     * Fetches a Quizlet/Space-importable TSV: the approved question bank (prompt -> answer) or,
     * with [headingsSource], the R5 headings (title -> chapter; [chapter] scopes cumulatively).
     */
    suspend fun questionsTsv(headingsSource: Boolean = false, round: Round? = null, chapter: Int? = null): ByteArray =
        client.get("$baseUrl/generate/questions.tsv") {
            authorize()
            if (headingsSource) parameter("source", "headings")
            if (round != null) parameter("round", round.name)
            if (chapter != null) parameter("chapter", chapter)
        }.bodyOrThrow()

    /** Fetches a Kahoot-importable .xlsx (multiple-choice material only; params as [questionsTsv]). */
    suspend fun questionsXlsx(headingsSource: Boolean = false, round: Round? = null, chapter: Int? = null): ByteArray =
        client.get("$baseUrl/generate/questions.xlsx") {
            authorize()
            if (headingsSource) parameter("source", "headings")
            if (round != null) parameter("round", round.name)
            if (chapter != null) parameter("chapter", chapter)
        }.bodyOrThrow()

    // --- Registration (coach flow; docs/gui-redesign.md §5E) ---

    /** Creates a congregation; the server grants the caller COACH scoped to it. 409 if it exists. */
    suspend fun createCongregation(req: CreateCongregationRequest): CongregationDto =
        client.post("$baseUrl/congregations") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** Case-insensitive congregation search (step-1 typeahead). */
    suspend fun searchCongregations(query: String): List<CongregationDto> =
        client.get("$baseUrl/congregations") { authorize(); parameter("query", query) }.bodyOrThrow()

    /** Suggests an available two-letter code derived from a congregation name (register form prefill). */
    suspend fun suggestCongregationCode(name: String): String =
        client.get("$baseUrl/congregations/code-suggestion") { authorize(); parameter("name", name) }
            .bodyOrThrow<CodeSuggestionResponse>().code

    /**
     * Edits a congregation's contact details (coach, window open); the two-letter state is fixed at
     * creation, so it isn't sent. 404 if unknown, 409 if the new name+city collides with another.
     */
    suspend fun updateCongregation(id: String, req: UpdateCongregationRequest): CongregationDto =
        client.put("$baseUrl/congregations/$id") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** The register screen's resume fetch: coached congregations, current registration, window state. */
    suspend fun myRegistration(): MyRegistrationResponse =
        client.get("$baseUrl/registration/mine") { authorize() }.bodyOrThrow()

    /** Pins the congregation's registration to a season event site (multi-site seasons; creates the draft). */
    suspend fun setRegistrationSite(congregationId: String, siteId: String): RegistrationUpdateResponse =
        client.put("$baseUrl/registration/$congregationId/site") {
            authorize(); contentType(ContentType.Application.Json); setBody(SetRegistrationSiteRequest(siteId))
        }.bodyOrThrow()

    /** Adds a team to the congregation's current-season registration (created on first team). */
    suspend fun addTeam(congregationId: String, name: String): RegistrationUpdateResponse =
        client.post("$baseUrl/registration/$congregationId/teams") {
            authorize(); contentType(ContentType.Application.Json); setBody(UpsertTeamRequest(name))
        }.bodyOrThrow()

    suspend fun renameTeam(teamId: String, name: String): RegistrationUpdateResponse =
        client.put("$baseUrl/registration/teams/$teamId") {
            authorize(); contentType(ContentType.Application.Json); setBody(UpsertTeamRequest(name))
        }.bodyOrThrow()

    suspend fun deleteTeam(teamId: String): RegistrationUpdateResponse =
        client.delete("$baseUrl/registration/teams/$teamId") { authorize() }.bodyOrThrow()

    /** Adds a roster entry (server enforces the 4-contestant cap and generates the claim code). */
    suspend fun addRosterEntry(teamId: String, req: UpsertRosterEntryRequest): RegistrationUpdateResponse =
        client.post("$baseUrl/registration/teams/$teamId/members") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun updateRosterEntry(memberId: String, req: UpsertRosterEntryRequest): RegistrationUpdateResponse =
        client.put("$baseUrl/registration/members/$memberId") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun deleteRosterEntry(memberId: String): RegistrationUpdateResponse =
        client.delete("$baseUrl/registration/members/$memberId") { authorize() }.bodyOrThrow()

    /**
     * Moves a youth contestant to [teamId] (same registration, ≤4), or frees it to the unassigned
     * pool when [teamId] is null. 409 when the target team is full. Also usable by a registrar.
     */
    suspend fun assignMemberTeam(memberId: String, teamId: String?): RegistrationUpdateResponse =
        client.put("$baseUrl/registration/members/$memberId/team") {
            authorize(); contentType(ContentType.Application.Json); setBody(AssignMemberTeamRequest(teamId))
        }.bodyOrThrow()

    /**
     * Enrolls a returning contestant into the current season — creates this year's roster entry from
     * the durable contestant, on [teamId] (or unassigned when null), with a freshly-collected
     * [shirtSize]. Like every registration mutation, the response carries the pared candidate list —
     * no [myRegistration] refetch needed.
     */
    /** [birthdate] is required for a workbook-seeded youth's first enrollment (records the real one). */
    suspend fun enrollContestant(
        congregationId: String,
        contestantId: String,
        shirtSize: ShirtSize,
        teamId: String? = null,
        birthdate: String? = null,
    ): RegistrationUpdateResponse =
        client.post("$baseUrl/registration/$congregationId/contestants/$contestantId/enroll") {
            authorize(); contentType(ContentType.Application.Json)
            setBody(EnrollContestantRequest(shirtSize, teamId, birthdate?.takeIf { it.isNotBlank() }))
        }.bodyOrThrow()

    /** Adds an individual (adult) contestant — adults compete individually, never on a team. */
    suspend fun addIndividual(congregationId: String, req: UpsertIndividualRequest): RegistrationUpdateResponse =
        client.post("$baseUrl/registration/$congregationId/individuals") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun updateIndividual(individualId: String, req: UpsertIndividualRequest): RegistrationUpdateResponse =
        client.put("$baseUrl/registration/individuals/$individualId") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun deleteIndividual(individualId: String): RegistrationUpdateResponse =
        client.delete("$baseUrl/registration/individuals/$individualId") { authorize() }.bodyOrThrow()

    /** Adds a registered guest — pays by age tier (9+ volunteer, 3–8 child, under-3 free) but is not a contestant. */
    suspend fun addGuest(congregationId: String, req: UpsertGuestRequest): RegistrationUpdateResponse =
        client.post("$baseUrl/registration/$congregationId/guests") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun updateGuest(guestId: String, req: UpsertGuestRequest): RegistrationUpdateResponse =
        client.put("$baseUrl/registration/guests/$guestId") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    suspend fun deleteGuest(guestId: String): RegistrationUpdateResponse =
        client.delete("$baseUrl/registration/guests/$guestId") { authorize() }.bodyOrThrow()

    /** Submits (or re-submits) the congregation's registration for the current season. */
    suspend fun submitRegistration(congregationId: String): RegistrationUpdateResponse =
        client.post("$baseUrl/registration/$congregationId/submit") { authorize() }.bodyOrThrow()

    /** Claims a roster entry by its coach-shared code (dashes/case ignored). 404/409 on bad codes. */
    suspend fun claimRosterEntry(code: String): RosterEntryDto =
        client.post("$baseUrl/roster/claim") {
            authorize(); contentType(ContentType.Application.Json); setBody(ClaimEntryRequest(code))
        }.bodyOrThrow()

    // --- Person-centric registration (schema redesign phase 4) ---

    /**
     * Claims a *person* by their coach-shared code (dashes/case ignored) — the person-centric
     * replacement for [claimRosterEntry]. The response says whether the account now IS the person
     * ([PersonRelation.SELF]) or manages them ([PersonRelation.MANAGED]). 400/404/409 on bad codes.
     */
    suspend fun claimPerson(code: String): ClaimPersonResponse =
        client.post("$baseUrl/people/claim") {
            authorize(); contentType(ContentType.Application.Json); setBody(ClaimPersonRequest(code))
        }.bodyOrThrow()

    /** Every person the signed-in account is or manages, with their participations across seasons. */
    suspend fun myPeople(): MyPeopleResponse =
        client.get("$baseUrl/people/mine") { authorize() }.bodyOrThrow()

    /**
     * Registrar tool: people whose name matches [query] (blank lists all), with participations —
     * the lookup that feeds the merge tool (event-wide REGISTRATION_MANAGE).
     */
    suspend fun searchPeople(query: String): List<PersonWithParticipationsDto> =
        client.get("$baseUrl/admin/people") { authorize(); parameter("query", query) }
            .bodyOrThrow<PeopleSearchResponse>().people

    /**
     * Registrar tool: merges [mergeId] into [keepId] (event-wide REGISTRATION_MANAGE). 409 when the
     * two share a season (resolve the duplicate registration there first).
     */
    suspend fun mergePeople(keepId: String, mergeId: String): MergePeopleResponse =
        client.post("$baseUrl/admin/people/merge") {
            authorize(); contentType(ContentType.Application.Json); setBody(MergePeopleRequest(keepId, mergeId))
        }.bodyOrThrow()

    // --- Scoring (grading desk, release, my scores; docs/gui-redesign.md §5F) ---

    /** The grading desk: every contestant this season with their entered scores (event-wide SCORE_ENTER). */
    suspend fun gradingSheet(): GradingSheetResponse =
        client.get("$baseUrl/admin/scores") { authorize() }.bodyOrThrow()

    /** Batch-saves grading grid cells (null points clears one); returns the refreshed sheet. */
    suspend fun saveScores(scores: List<ScoreEntryDto>): GradingSheetResponse =
        client.put("$baseUrl/admin/scores") {
            authorize(); contentType(ContentType.Application.Json); setBody(SaveScoresRequest(scores))
        }.bodyOrThrow()

    /** Releases or retracts the current season's scores (event-wide SCORE_RELEASE). */
    suspend fun setScoresReleased(released: Boolean): GradingSheetResponse =
        client.put("$baseUrl/admin/scores/release") {
            authorize(); contentType(ContentType.Application.Json); setBody(SetScoresReleasedRequest(released))
        }.bodyOrThrow()

    /** The signed-in user's visible released scores (owned entries + coached congregations). */
    suspend fun myScores(): MyScoresResponse =
        client.get("$baseUrl/scores/mine") { authorize() }.bodyOrThrow()

    /** The division tally (event-wide SCORE_VIEW_ALL): every bracket ranked as grading progresses. */
    suspend fun standings(): StandingsResponse =
        client.get("$baseUrl/admin/scores/standings") { authorize() }.bodyOrThrow()

    // --- Admin: registration desk & user management (docs/gui-redesign.md §5G) ---

    /**
     * The full registration desk — the current season by default, or a past [year] for review.
     * Requires event-wide REGISTRATION_MANAGE.
     */
    suspend fun registrationDesk(year: String? = null): RegistrationDeskResponse =
        client.get("$baseUrl/admin/registrations") {
            authorize()
            if (year != null) parameter("year", year)
        }.bodyOrThrow()

    /**
     * Every tester this season with per-site tester IDs and ZipGrade external IDs; fetching lazily
     * assigns numbers to the not-yet-numbered (append-only — assigned IDs never change). Requires
     * event-wide REGISTRATION_MANAGE or SCORE_ENTER.
     */
    suspend fun adminTesters(): TesterListResponse =
        client.get("$baseUrl/admin/testers") { authorize() }.bodyOrThrow()

    /**
     * Printable per-site nametags (item 14, F8); generating also assigns any missing tester IDs
     * (same append-only scheme as [adminTesters]). Registrar/admin only (attendee names include
     * minors'), hence the authenticated fetch rather than a public /generate link. Null [siteId] =
     * every site, one per-site page stack.
     */
    suspend fun nametagsPdf(siteId: String? = null): ByteArray =
        client.get("$baseUrl/admin/registrations/nametags.pdf") {
            authorize()
            if (siteId != null) parameter("siteId", siteId)
        }.bodyOrThrow()

    /** Marks a registration's payment received, or clears it. Requires event-wide REGISTRATION_MANAGE. */
    suspend fun setRegistrationPaid(registrationId: String, paid: Boolean): RegistrationDto =
        client.put("$baseUrl/admin/registrations/$registrationId/paid") {
            authorize(); contentType(ContentType.Application.Json); setBody(SetPaidRequest(paid))
        }.bodyOrThrow()

    // --- Admin: housing / cabin assignments (item 15, F9) — all REGISTRATION_MANAGE ---

    /** The season's cabins with their assignment rows plus the check-out duty roster. */
    suspend fun housing(): HousingResponse =
        client.get("$baseUrl/admin/housing") { authorize() }.bodyOrThrow()

    /** Adds a cabin (name unique per season + site); returns the refreshed housing picture. */
    suspend fun addCabin(req: UpsertCabinRequest): HousingResponse =
        client.post("$baseUrl/admin/housing/cabins") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** Renames/re-sites/re-sizes a cabin; returns the refreshed housing picture. */
    suspend fun updateCabin(cabinId: String, req: UpsertCabinRequest): HousingResponse =
        client.put("$baseUrl/admin/housing/cabins/$cabinId") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** Deletes a cabin and its assignment rows; returns the refreshed housing picture. */
    suspend fun deleteCabin(cabinId: String): HousingResponse =
        client.delete("$baseUrl/admin/housing/cabins/$cabinId") { authorize() }.bodyOrThrow()

    /** Adds an assignment row (congregation × gender group and/or free-text label) to a cabin. */
    suspend fun addCabinAssignment(cabinId: String, req: AddCabinAssignmentRequest): HousingResponse =
        client.post("$baseUrl/admin/housing/cabins/$cabinId/assignments") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** Removes an assignment row; returns the refreshed housing picture. */
    suspend fun deleteCabinAssignment(assignmentId: String): HousingResponse =
        client.delete("$baseUrl/admin/housing/assignments/$assignmentId") { authorize() }.bodyOrThrow()

    /** Sets (non-blank) or clears (blank) a congregation's check-out duty adult. */
    suspend fun setCheckoutDuty(congregationId: String, adultName: String): HousingResponse =
        client.put("$baseUrl/admin/housing/checkout/$congregationId") {
            authorize(); contentType(ContentType.Application.Json); setBody(SetCheckoutDutyRequest(adultName))
        }.bodyOrThrow()

    // --- Admin: tribes & tribe leaders (item 16, F10) — all REGISTRATION_MANAGE ---

    /** The season's tribes with their assigned leaders. */
    suspend fun tribes(): TribesResponse =
        client.get("$baseUrl/admin/tribes") { authorize() }.bodyOrThrow()

    /** Adds a tribe (name unique per season + site); returns the refreshed tribe list. */
    suspend fun addTribe(req: UpsertTribeRequest): TribesResponse =
        client.post("$baseUrl/admin/tribes") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** Renames/re-sites a tribe; returns the refreshed tribe list. */
    suspend fun updateTribe(tribeId: String, req: UpsertTribeRequest): TribesResponse =
        client.put("$baseUrl/admin/tribes/$tribeId") {
            authorize(); contentType(ContentType.Application.Json); setBody(req)
        }.bodyOrThrow()

    /** Deletes a tribe and its leader rows; returns the refreshed tribe list. */
    suspend fun deleteTribe(tribeId: String): TribesResponse =
        client.delete("$baseUrl/admin/tribes/$tribeId") { authorize() }.bodyOrThrow()

    /** Assigns a leader (free-form adult name) to a tribe; returns the refreshed tribe list. */
    suspend fun addTribeLeader(tribeId: String, name: String): TribesResponse =
        client.post("$baseUrl/admin/tribes/$tribeId/leaders") {
            authorize(); contentType(ContentType.Application.Json); setBody(AddTribeLeaderRequest(name))
        }.bodyOrThrow()

    /** Removes an assigned leader; returns the refreshed tribe list. */
    suspend fun deleteTribeLeader(leaderId: String): TribesResponse =
        client.delete("$baseUrl/admin/tribes/leaders/$leaderId") { authorize() }.bodyOrThrow()

    /** Searches users by name/email fragment (USER_MANAGE). A blank query returns nothing. */
    suspend fun searchUsers(query: String): List<UserDto> =
        client.get("$baseUrl/users") { authorize(); parameter("query", query) }.bodyOrThrow()

    /** Grants [grant] to [userId] (ROLE_GRANT); returns the updated user. Idempotent. */
    suspend fun grantRole(userId: String, grant: RoleGrant): UserDto =
        client.post("$baseUrl/users/$userId/roles") {
            authorize(); contentType(ContentType.Application.Json); setBody(grant)
        }.bodyOrThrow()

    /** Revokes [grant] from [userId] (grant identity as query params — DELETE bodies are unreliable). */
    suspend fun revokeRole(userId: String, grant: RoleGrant): UserDto =
        client.delete("$baseUrl/users/$userId/roles") {
            authorize()
            parameter("role", grant.role.name)
            parameter("scopeType", grant.scopeType.name)
            grant.scopeId?.let { parameter("scopeId", it) }
        }.bodyOrThrow()

    /**
     * Drops every server-cached generated PDF so the next download of each regenerates (for when the
     * generation code changes; requires SEASON_MANAGE). Returns how many were cleared.
     */
    suspend fun clearPdfCache(): ClearPdfCacheResponse =
        client.delete("$baseUrl/generate/cache") { authorize() }.bodyOrThrow()

    /**
     * Returns the response body decoded as [T], or throws [ApiException] with the server's error message on
     * a non-2xx status. Without this the client would try to deserialize an [ApiError] body as [T] and
     * surface a cryptic JsonConvertException dump — or, for byte endpoints, happily "download" the JSON
     * error body as a `.pdf` that the browser then reports as corrupt with no clue what actually went wrong.
     */
    private suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
        if (status.isSuccess()) return body()
        val raw = runCatching { bodyAsText() }.getOrNull()
        val error = raw?.let { runCatching { errorJson.decodeFromString<ApiError>(it) }.getOrNull() }
        val message = error?.message
            ?: raw?.takeIf { it.isNotBlank() }
            ?: "Server returned $status"
        throw ApiException(message, status.value, error?.code)
    }

    companion object {
        private val errorJson = Json { ignoreUnknownKeys = true }

        // Local dev default; the web build points at the deployed Cloud Run URL at build time.
        const val DEFAULT_BASE_URL = "http://localhost:8080"
    }
}

/**
 * Thrown when a backend request fails; [message] carries the server's human-readable reason,
 * [status] the HTTP status code (null when the failure happened before a response arrived), and
 * [errorCode] the machine-readable [ApiError.code] when the body carried one (lets callers branch
 * on the specific error, e.g. "code_taken" vs "congregation_exists").
 */
class ApiException(message: String, val status: Int? = null, val errorCode: String? = null) : Exception(message)
