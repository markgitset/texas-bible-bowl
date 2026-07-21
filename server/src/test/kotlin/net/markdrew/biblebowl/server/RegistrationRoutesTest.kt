package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AssignMemberTeamRequest
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.CodeSuggestionResponse
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.contestantCount
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.EnrollContestantRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryCongregationRepository
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryRegistrationRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A season whose registration window is open "now". */
    private val openSeason = DEFAULT_SEASON.copy(
        priceContestantCents = 8500,
        registrationOpensOn = "2000-01-01",
        registrationClosesOn = "2999-12-31",
        registrationEnabled = true,
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    /** Coaches default to adult accounts — only adults may create congregations. */
    private suspend fun HttpClient.signUp(email: String, name: String, adult: Boolean = true): AuthResponse =
        json.decodeFromString(
            post("/auth/register") {
                setBody(RegisterRequest(email, "password123", name, birthdate = "2013-05-01".takeUnless { adult }, adult = adult))
            }.bodyAsText()
        )

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

    private fun congregationRequest(name: String, city: String) = CreateCongregationRequest(
        name = name, city = city, state = "TX", mailingAddress = "123 Main St", zip = "78701",
    )

    @Test
    fun creatingACongregationGrantsScopedCoach() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        assertFalse(Permission.TEAM_MANAGE in coach.user.permissions, "not a coach yet")

        // Missing address fields (state defaults to blank here) are a 400.
        val invalid = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(CreateCongregationRequest("First Church", "Austin"))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("invalid_congregation", invalid.body<ApiError>().code)

        val res = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(congregationRequest("First Church", "Austin").copy(state = "tx"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val congregation: CongregationDto = res.body()
        assertEquals("TX", congregation.state, "state is normalized to uppercase")
        assertEquals("123 Main St", congregation.mailingAddress)
        assertEquals("78701", congregation.zip)

        // The grant is scoped to the new congregation and visible via /auth/me.
        val me: UserDto = api.get("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
        }.body()
        assertTrue(Permission.TEAM_MANAGE in me.permissions)
        assertTrue(me.roles.any { it.role == Role.COACH && it.scopeId == congregation.id })

        // A duplicate congregation (case-insensitive) is a 409 with the "contact us" signal.
        val other = api.signUp("other@tbb.org", "Other Coach")
        val dupe = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(congregationRequest("FIRST CHURCH", "austin"))
        }
        assertEquals(HttpStatusCode.Conflict, dupe.status)
        assertEquals("congregation_exists", dupe.body<ApiError>().code)

        // Anonymous congregation creation is 401.
        val anon = api.post("/congregations") { setBody(CreateCongregationRequest("X", "Y")) }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)
    }

    @Test
    fun onlyAdultsMayCreateCongregations() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val youth = api.signUp("kid@tbb.org", "Timothy", adult = false)
        val refused = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${youth.token}")
            setBody(congregationRequest("Kid Church", "Lystra"))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("adult_required", refused.body<ApiError>().code)

        // After marking themselves an adult on the account page, creation succeeds.
        api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${youth.token}")
            setBody(net.markdrew.biblebowl.api.UpdateProfileRequest("Timothy", adult = true))
        }
        val allowed = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${youth.token}")
            setBody(congregationRequest("Kid Church", "Lystra"))
        }
        assertEquals(HttpStatusCode.Created, allowed.status)
    }

    @Test
    fun creatingACongregationSuggestsAndStoresATwoLetterCode() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        fun io.ktor.client.request.HttpRequestBuilder.asCoach() =
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")

        // The suggestion endpoint derives an available code from the name.
        val suggested: CodeSuggestionResponse =
            api.get("/congregations/code-suggestion?name=West%20Bexar%20County%20Church%20of%20Christ") { asCoach() }.body()
        assertEquals("WB", suggested.code)

        // Create with a code (lowercase) — stored uppercased.
        val cong: CongregationDto = api.post("/congregations") {
            asCoach(); setBody(congregationRequest("West Bexar County Church of Christ", "San Antonio").copy(code = "wb"))
        }.body()
        assertEquals("WB", cong.code)

        // With WB taken, the suggestion for the same name moves on to WC.
        val next: CodeSuggestionResponse =
            api.get("/congregations/code-suggestion?name=West%20Bexar%20County%20Church%20of%20Christ") { asCoach() }.body()
        assertEquals("WC", next.code)

        // A second congregation can't grab WB, and a non-two-letter code is a 400.
        val other = api.signUp("other@tbb.org", "Other Coach")
        val taken = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(congregationRequest("Westside Baptist", "Dallas").copy(code = "WB"))
        }
        assertEquals(HttpStatusCode.Conflict, taken.status)
        assertEquals("code_taken", taken.body<ApiError>().code)
        val bad = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(congregationRequest("Third Church", "Waco").copy(code = "W1"))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("invalid_code", bad.body<ApiError>().code)
    }

    @Test
    fun coachEditsCongregationAndPicksACodeOnlyAnAdminCanLaterChange() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        fun io.ktor.client.request.HttpRequestBuilder.asCoach() =
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")

        val congregation: CongregationDto = api.post("/congregations") {
            asCoach(); setBody(congregationRequest("First Church", "Austin"))
        }.body()
        assertEquals("", congregation.code, "a new congregation has no code yet")

        // Name, city, and state are all editable; the coach also picks a two-letter code (uppercased).
        val edited: CongregationDto = api.put("/congregations/${congregation.id}") {
            asCoach()
            setBody(UpdateCongregationRequest("First Christian Church", "Round Rock", state = "ok", mailingAddress = "456 Oak Ave", zip = "78664", code = "fc"))
        }.body()
        assertEquals("First Christian Church", edited.name)
        assertEquals("Round Rock", edited.city)
        assertEquals("OK", edited.state, "state is editable now")
        assertEquals("FC", edited.code, "code is stored uppercased")

        // The edit round-trips through the resume fetch.
        val mine: MyRegistrationResponse = api.get("/registration/mine") { asCoach() }.body()
        assertEquals("FC", mine.congregations.single().code)

        // Re-saving other fields while keeping the same code is fine.
        val keptCode: CongregationDto = api.put("/congregations/${congregation.id}") {
            asCoach()
            setBody(UpdateCongregationRequest("First Christian Church", "Round Rock", state = "TX", mailingAddress = "456 Oak Ave", zip = "78664", code = "FC"))
        }.body()
        assertEquals("TX", keptCode.state)
        assertEquals("FC", keptCode.code)

        // A coach cannot *change* the code once it's set.
        val lockedChange = api.put("/congregations/${congregation.id}") {
            asCoach()
            setBody(UpdateCongregationRequest("First Christian Church", "Round Rock", state = "TX", mailingAddress = "456 Oak Ave", zip = "78664", code = "ZZ"))
        }
        assertEquals(HttpStatusCode.Forbidden, lockedChange.status)
        assertEquals("forbidden_code_change", lockedChange.body<ApiError>().code)

        // A code that isn't two letters is a 400; a blank required field (state) is also a 400.
        val badCode = api.put("/congregations/${congregation.id}") {
            asCoach(); setBody(UpdateCongregationRequest("First Christian Church", "Round Rock", state = "TX", mailingAddress = "456 Oak Ave", zip = "78664", code = "F1"))
        }
        assertEquals(HttpStatusCode.BadRequest, badCode.status)
        assertEquals("invalid_code", badCode.body<ApiError>().code)
        val blankState = api.put("/congregations/${congregation.id}") {
            asCoach(); setBody(UpdateCongregationRequest("First Christian Church", "Round Rock", state = "", mailingAddress = "456 Oak Ave", zip = "78664", code = "FC"))
        }
        assertEquals(HttpStatusCode.BadRequest, blankState.status)
        assertEquals("invalid_congregation", blankState.body<ApiError>().code)

        // A second congregation can't grab a taken code, and renaming onto another's name+city clashes.
        val other = api.signUp("other@tbb.org", "Other Coach")
        val cong2: CongregationDto = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(congregationRequest("Second Church", "Dallas"))
        }.body()
        val codeClash = api.put("/congregations/${cong2.id}") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(UpdateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "1 St", zip = "75001", code = "fc"))
        }
        assertEquals(HttpStatusCode.Conflict, codeClash.status)
        assertEquals("code_taken", codeClash.body<ApiError>().code)
        val nameCityClash = api.put("/congregations/${congregation.id}") {
            asCoach(); setBody(UpdateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "456 Oak Ave", zip = "78664", code = "FC"))
        }
        assertEquals(HttpStatusCode.Conflict, nameCityClash.status)
        assertEquals("congregation_exists", nameCityClash.body<ApiError>().code)

        // A different coach can't edit the first congregation at all (scoped grant).
        val forbidden = api.put("/congregations/${congregation.id}") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(UpdateCongregationRequest("Hostile Takeover", "Dallas", state = "TX", mailingAddress = "1 St", zip = "75001", code = "FC"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("forbidden_scope", forbidden.body<ApiError>().code)

        // An admin *can* change a set code.
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") { setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret")) }.bodyAsText()
        )
        val adminChanged: CongregationDto = api.put("/congregations/${congregation.id}") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(UpdateCongregationRequest("First Christian Church", "Round Rock", state = "TX", mailingAddress = "456 Oak Ave", zip = "78664", code = "FA"))
        }.body()
        assertEquals("FA", adminChanged.code)
    }

    @Test
    fun editingCongregationIsWindowGatedButAdminsMayFixItLate() = testApplication {
        val users = InMemoryUserRepository()
        val closedSeason = openSeason.copy(registrationClosesOn = "2000-12-31")
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(closedSeason))
        }
        val api = jsonClient()

        // Creating (onboarding) still works after close; editing details does not, for a coach.
        val coach = api.signUp("late@tbb.org", "Late Coach")
        val congregation: CongregationDto = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(congregationRequest("Late Church", "Waco"))
        }.body()
        val closed = api.put("/congregations/${congregation.id}") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(UpdateCongregationRequest("Late Church Renamed", "Waco", state = "TX", mailingAddress = "123 Main St", zip = "78701"))
        }
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals("registration_closed", closed.body<ApiError>().code)

        // Admins may still fix a congregation after the deadline.
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        val adminEdit: CongregationDto = api.put("/congregations/${congregation.id}") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(UpdateCongregationRequest("Late Church Renamed", "Waco", state = "TX", mailingAddress = "123 Main St", zip = "78701"))
        }.body()
        assertEquals("Late Church Renamed", adminEdit.name)
    }

    @Test
    fun coachWalksTheFullFlowAndResumes() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        fun io.ktor.client.request.HttpRequestBuilder.asCoach() =
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")

        val congregation: CongregationDto = api.post("/congregations") {
            asCoach(); setBody(congregationRequest("Flow Church", "Waco"))
        }.body()

        // Resume fetch before any team: congregation listed, no registration yet, window open.
        val before: MyRegistrationResponse = api.get("/registration/mine") { asCoach() }.body()
        assertEquals(listOf(congregation), before.congregations)
        assertNull(before.registration)
        assertTrue(before.windowOpen)

        // Teams + roster.
        val withTeam: RegistrationDto = api.post("/registration/${congregation.id}/teams") {
            asCoach(); setBody(UpsertTeamRequest("Team Alpha"))
        }.body()
        assertEquals(RegistrationStatus.DRAFT, withTeam.status)
        val teamId = withTeam.teams.single().id

        val withMember: RegistrationDto = api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Timothy", "2013-05-01", ShirtSize.YM, Gender.MALE, inexperienced = true))
        }.body()
        val entry = withMember.teams.single().members.single()
        assertEquals("2013-05-01", entry.birthdate)
        assertEquals(Gender.MALE, entry.gender)
        assertEquals(DEFAULT_SEASON.eventYear, entry.firstSeasonYear, "inexperienced → first season is this one")
        assertEquals(8, entry.claimCode.length)
        assertEquals(8500, withMember.totalCents, "1 contestant × \$85")

        // Roster cap: 4 max.
        repeat(3) { i ->
            api.post("/registration/teams/$teamId/members") {
                asCoach(); setBody(UpsertRosterEntryRequest("Kid $i", "2016-05-01", ShirtSize.YL, Gender.FEMALE))
            }
        }
        val fifth = api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Fifth", "2015-05-01", ShirtSize.YL, Gender.MALE))
        }
        assertEquals(HttpStatusCode.Conflict, fifth.status)
        assertEquals("roster_full", fifth.body<ApiError>().code)

        // A birthdate below school age (no division) is rejected, as is a malformed one.
        val tooYoung = api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Toddler", "2023-01-01", ShirtSize.YS, Gender.MALE))
        }
        assertEquals(HttpStatusCode.BadRequest, tooYoung.status)
        val badDate = api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Mystery", "May 2013", ShirtSize.YS, Gender.MALE))
        }
        assertEquals(HttpStatusCode.BadRequest, badDate.status)

        // Adults can't be placed on a team — they register as individual contestants.
        val withAdult: RegistrationDto = api.post("/registration/${congregation.id}/individuals") {
            asCoach(); setBody(UpsertIndividualRequest("Pat Adult", ShirtSize.AXL, Gender.FEMALE))
        }.body()
        val adult = withAdult.individuals.single()
        assertNull(adult.birthdate, "individuals never carry a birthdate")
        assertEquals(Gender.FEMALE, adult.gender)
        assertNull(adult.firstSeasonYear, "no experience split in the Adult division")
        assertEquals(8, adult.claimCode.length)
        assertEquals(5 * 8500, withAdult.totalCents, "4 team members + 1 individual")

        val editedAdult: RegistrationDto = api.put("/registration/individuals/${adult.id}") {
            asCoach(); setBody(UpsertIndividualRequest("Pat A.", ShirtSize.AM, Gender.FEMALE))
        }.body()
        assertEquals("Pat A.", editedAdult.individuals.single().name)

        // Submit, then resume shows SUBMITTED with the full roster and total.
        val submitted: RegistrationDto = api.post("/registration/${congregation.id}/submit") { asCoach() }.body()
        assertEquals(RegistrationStatus.SUBMITTED, submitted.status)
        assertNotNull(submitted.submittedAt)
        assertEquals(5 * 8500, submitted.totalCents)

        val resumed: MyRegistrationResponse = api.get("/registration/mine") { asCoach() }.body()
        assertEquals(RegistrationStatus.SUBMITTED, resumed.registration?.status)
        assertEquals(4, resumed.registration?.teams?.single()?.members?.size)

        // Still editable until the deadline: rename team, edit + delete a member, re-submit.
        val renamed: RegistrationDto = api.put("/registration/teams/$teamId") {
            asCoach(); setBody(UpsertTeamRequest("Team Omega"))
        }.body()
        assertEquals("Team Omega", renamed.teams.single().name)
        val memberId = renamed.teams.single().members.first().id
        val afterDelete: RegistrationDto = api.delete("/registration/members/$memberId") { asCoach() }.body()
        assertEquals(3, afterDelete.teams.single().members.size)
        val afterAdultDelete: RegistrationDto =
            api.delete("/registration/individuals/${adult.id}") { asCoach() }.body()
        assertEquals(0, afterAdultDelete.individuals.size)
        assertEquals(3 * 8500, afterAdultDelete.totalCents)
        assertEquals(HttpStatusCode.OK, api.post("/registration/${congregation.id}/submit") { asCoach() }.status)
    }

    @Test
    fun guestsRegisterAndPayButAreNotContestants() = testApplication {
        val users = InMemoryUserRepository()
        // Guest fees: adults/volunteers $40, children (3–8) $25.
        val season = openSeason.copy(priceVolunteerCents = 4000, priceChildCents = 2500)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season))
        }
        val api = jsonClient()
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        fun io.ktor.client.request.HttpRequestBuilder.asCoach() =
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")

        val cong: CongregationDto = api.post("/congregations") {
            asCoach(); setBody(congregationRequest("Guest Church", "Waco"))
        }.body()

        // A guest-only registration is billable: guests pay even though they aren't contestants.
        val blank = api.post("/registration/${cong.id}/guests") {
            asCoach(); setBody(UpsertGuestRequest("  ", ShirtSize.AM))
        }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        val withVolunteer: RegistrationDto = api.post("/registration/${cong.id}/guests") {
            asCoach(); setBody(UpsertGuestRequest("Aunt Vol", ShirtSize.AM))
        }.body()
        val volunteer = withVolunteer.guests.single()
        assertFalse(volunteer.child)
        assertEquals(0, withVolunteer.contestantCount, "guests are not contestants")
        assertEquals(4000, withVolunteer.totalCents, "adult guest at the volunteer fee")

        // A contestant plus a child guest: contestant + volunteer + child fees.
        val teamId = api.post("/registration/${cong.id}/teams") {
            asCoach(); setBody(UpsertTeamRequest("Team A"))
        }.body<RegistrationDto>().teams.single().id
        api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Timothy", "2013-05-01", ShirtSize.YM, Gender.MALE))
        }
        val withChild: RegistrationDto = api.post("/registration/${cong.id}/guests") {
            asCoach(); setBody(UpsertGuestRequest("Little Sib", ShirtSize.YS, child = true))
        }.body()
        assertEquals(1, withChild.contestantCount)
        assertEquals(8500 + 4000 + 2500, withChild.totalCents)

        // Edit and delete round-trip, and the total follows.
        val edited: RegistrationDto = api.put("/registration/guests/${volunteer.id}") {
            asCoach(); setBody(UpsertGuestRequest("Aunt V.", ShirtSize.AL))
        }.body()
        assertEquals("Aunt V.", edited.guests.first { it.id == volunteer.id }.name)
        val afterDelete: RegistrationDto = api.delete("/registration/guests/${volunteer.id}") { asCoach() }.body()
        assertEquals(8500 + 2500, afterDelete.totalCents)
        val missing = api.delete("/registration/guests/${volunteer.id}") { asCoach() }
        assertEquals(HttpStatusCode.NotFound, missing.status)

        // Only the congregation's own coach (or an event-wide manager) may touch its guests.
        val stranger = api.signUp("other@tbb.org", "Other Adult")
        val forbidden = api.post("/registration/${cong.id}/guests") {
            header(HttpHeaders.Authorization, "Bearer ${stranger.token}")
            setBody(UpsertGuestRequest("Party Crasher", ShirtSize.AM))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
    }

    @Test
    fun deletingATeamUnassignsMembersWhoCanBeReassignedThenSubmitted() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        fun io.ktor.client.request.HttpRequestBuilder.asCoach() =
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")

        val cong: CongregationDto = api.post("/congregations") {
            asCoach(); setBody(congregationRequest("Free Church", "Waco"))
        }.body()
        val teamA = api.post("/registration/${cong.id}/teams") {
            asCoach(); setBody(UpsertTeamRequest("Team A"))
        }.body<RegistrationDto>().teams.single().id
        val memberId = api.post("/registration/teams/$teamA/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Timothy", "2013-05-01", ShirtSize.YM, Gender.MALE))
        }.body<RegistrationDto>().teams.single().members.single().id

        // Deleting the team frees the member to the unassigned pool — not deleted, still billed.
        val afterDelete: RegistrationDto = api.delete("/registration/teams/$teamA") { asCoach() }.body()
        assertTrue(afterDelete.teams.isEmpty())
        assertEquals(listOf("Timothy"), afterDelete.unassigned.map { it.name })
        assertEquals(8500, afterDelete.totalCents, "the unassigned contestant is still one paid contestant")

        // A registration may be submitted with unassigned contestants (a registrar places them).
        val submitted: RegistrationDto = api.post("/registration/${cong.id}/submit") { asCoach() }.body()
        assertEquals(RegistrationStatus.SUBMITTED, submitted.status)
        assertEquals(1, submitted.unassigned.size)

        // Assigning the unassigned contestant onto a fresh team empties the pool.
        val teamB = api.post("/registration/${cong.id}/teams") {
            asCoach(); setBody(UpsertTeamRequest("Team B"))
        }.body<RegistrationDto>().teams.single { it.name == "Team B" }.id
        val assigned: RegistrationDto = api.put("/registration/members/$memberId/team") {
            asCoach(); setBody(AssignMemberTeamRequest(teamB))
        }.body()
        assertTrue(assigned.unassigned.isEmpty())
        assertEquals(listOf("Timothy"), assigned.teams.single { it.id == teamB }.members.map { it.name })

        // Unassigning again (null team) puts them back in the pool.
        val backInPool: RegistrationDto = api.put("/registration/members/$memberId/team") {
            asCoach(); setBody(AssignMemberTeamRequest(null))
        }.body()
        assertEquals(listOf("Timothy"), backInPool.unassigned.map { it.name })
    }

    @Test
    fun returningContestantsAreOfferedAndCanBeEnrolled() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason), congregations = congregations, registrations = registrations)
        }
        val api = jsonClient()
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        fun io.ktor.client.request.HttpRequestBuilder.asCoach() =
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")

        val cong: CongregationDto = api.post("/congregations") {
            asCoach(); setBody(congregationRequest("Return Church", "Waco"))
        }.body()

        // Seed a prior season (2026) enrollment directly, so the person is a returning candidate for 2027.
        val oldTeam = registrations.addTeam(cong.id, "2026", "Old Team")!!
        registrations.addMember(oldTeam.id, UpsertRosterEntryRequest("Timothy", "2013-05-01", ShirtSize.YM, Gender.MALE))

        // The current-season resume offers Timothy as a returning candidate (not yet billed).
        val mine: MyRegistrationResponse = api.get("/registration/mine") { asCoach() }.body()
        assertEquals(listOf("Timothy"), mine.returningCandidates.map { it.name })
        assertEquals("2026", mine.returningCandidates.single().lastSeasonYear)
        val contestantId = mine.returningCandidates.single().contestantId

        // Enrolling him into 2027 puts him on the roster (unassigned) and starts billing him.
        val afterEnroll: RegistrationDto = api.post("/registration/${cong.id}/contestants/$contestantId/enroll") {
            asCoach(); setBody(EnrollContestantRequest(ShirtSize.YL))
        }.body()
        assertEquals(listOf("Timothy"), afterEnroll.unassigned.map { it.name })
        assertEquals(8500, afterEnroll.totalCents, "enrolling counts him as one paid contestant")

        // He's no longer a candidate, and re-enrolling / a bogus contestant are both rejected.
        assertTrue(api.get("/registration/mine") { asCoach() }.body<MyRegistrationResponse>().returningCandidates.isEmpty())
        assertEquals(HttpStatusCode.Conflict, api.post("/registration/${cong.id}/contestants/$contestantId/enroll") {
            asCoach(); setBody(EnrollContestantRequest(ShirtSize.YL))
        }.status)
        assertEquals(HttpStatusCode.Conflict, api.post("/registration/${cong.id}/contestants/nope/enroll") {
            asCoach(); setBody(EnrollContestantRequest(ShirtSize.YL))
        }.status)

        // A returning adult is offered too (birthdate-less) and enrolls as an individual, not a team member.
        registrations.addIndividual(cong.id, "2026", UpsertIndividualRequest("Pat Adult", ShirtSize.AL, Gender.FEMALE))
        val adult = api.get("/registration/mine") { asCoach() }.body<MyRegistrationResponse>().returningCandidates.single()
        assertEquals("Pat Adult", adult.name)
        assertNull(adult.birthdate, "an adult candidate has no birthdate")
        val enrolledAdult: RegistrationDto = api.post("/registration/${cong.id}/contestants/${adult.contestantId}/enroll") {
            asCoach(); setBody(EnrollContestantRequest(ShirtSize.AM))
        }.body()
        assertEquals(listOf("Pat Adult"), enrolledAdult.individuals.map { it.name })
    }

    @Test
    fun scopedGrantKeepsCoachesOutOfOtherCongregations() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val alice = api.signUp("alice@tbb.org", "Alice")
        val congA: CongregationDto = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            setBody(congregationRequest("Alpha Church", "Austin"))
        }.body()
        val teamA = api.post("/registration/${congA.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            setBody(UpsertTeamRequest("Alpha Team"))
        }.body<RegistrationDto>().teams.single()

        val bob = api.signUp("bob@tbb.org", "Bob")
        api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            setBody(congregationRequest("Beta Church", "Dallas"))
        }

        // Bob holds TEAM_MANAGE (he's a coach) but not for Alice's congregation.
        val addTeam = api.post("/registration/${congA.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            setBody(UpsertTeamRequest("Sneaky Team"))
        }
        assertEquals(HttpStatusCode.Forbidden, addTeam.status)
        assertEquals("forbidden_scope", addTeam.body<ApiError>().code)
        val addMember = api.post("/registration/teams/${teamA.id}/members") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            setBody(UpsertRosterEntryRequest("Mole", "2014-05-01", ShirtSize.AM, Gender.MALE))
        }
        assertEquals(HttpStatusCode.Forbidden, addMember.status)
        val addIndividual = api.post("/registration/${congA.id}/individuals") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            setBody(UpsertIndividualRequest("Mole Adult", ShirtSize.AM, Gender.MALE))
        }
        assertEquals(HttpStatusCode.Forbidden, addIndividual.status)
        val submit = api.post("/registration/${congA.id}/submit") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, submit.status)

        // An admin passes the scope check everywhere.
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        val adminAdd = api.post("/registration/${congA.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(UpsertTeamRequest("Admin Team"))
        }
        assertEquals(HttpStatusCode.OK, adminAdd.status)
    }

    @Test
    fun registrationWindowGatesMutationsButNotAdmins() = testApplication {
        val users = InMemoryUserRepository()
        val closedSeason = openSeason.copy(registrationClosesOn = "2000-12-31")
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(closedSeason))
        }
        val api = jsonClient()

        // Congregation creation (onboarding) is not window-gated.
        val coach = api.signUp("late@tbb.org", "Late Coach")
        val congregation: CongregationDto = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(congregationRequest("Late Church", "Waco"))
        }.body()

        // ...but team mutations are.
        val res = api.post("/registration/${congregation.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(UpsertTeamRequest("Too Late"))
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("registration_closed", res.body<ApiError>().code)

        val mine: MyRegistrationResponse = api.get("/registration/mine") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
        }.body()
        assertFalse(mine.windowOpen)

        // Admins may still fix registrations after the deadline.
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        val adminAdd = api.post("/registration/${congregation.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(UpsertTeamRequest("Admin Fixup"))
        }
        assertEquals(HttpStatusCode.OK, adminAdd.status)

        // An unannounced window (no opens date) reports registration_not_open.
        val unannounced = InMemorySeasonRepository(DEFAULT_SEASON)
        // (covered unit-side in RegistrationWindowTest; route-level state is exercised above)
        assertEquals(DEFAULT_SEASON, unannounced.current())
    }

    @Test
    fun registrationIsDarkUntilTheFeatureToggleIsOn() = testApplication {
        val users = InMemoryUserRepository()
        application {
            // Window wide open, but the feature itself hasn't launched (the deploy-dark default).
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason.copy(registrationEnabled = false)))
        }
        val api = jsonClient()

        // Every registration endpoint answers 403 feature_disabled, even reads.
        val coach = api.signUp("coach@tbb.org", "Carol Coach")
        val create = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(congregationRequest("First Church", "Austin"))
        }
        assertEquals(HttpStatusCode.Forbidden, create.status)
        assertEquals("feature_disabled", create.body<ApiError>().code)
        val mine = api.get("/registration/mine") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, mine.status)
        assertEquals("feature_disabled", mine.body<ApiError>().code)

        // Global admins bypass the toggle so the dark-deployed feature can be tested in prod.
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        val adminCreate = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(congregationRequest("First Church", "Austin"))
        }
        assertEquals(HttpStatusCode.Created, adminCreate.status)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/registration/mine") { header(HttpHeaders.Authorization, "Bearer ${admin.token}") }.status,
        )
    }
}
