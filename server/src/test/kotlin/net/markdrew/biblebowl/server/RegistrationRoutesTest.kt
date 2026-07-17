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
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ShirtSize
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
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private suspend fun HttpClient.signUp(email: String, name: String): AuthResponse =
        json.decodeFromString(
            post("/auth/register") { setBody(RegisterRequest(email, "password123", name)) }.bodyAsText()
        )

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

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

        val res = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(CreateCongregationRequest("First Church", "Austin"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val congregation: CongregationDto = res.body()

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
            setBody(CreateCongregationRequest("FIRST CHURCH", "austin"))
        }
        assertEquals(HttpStatusCode.Conflict, dupe.status)
        assertEquals("congregation_exists", dupe.body<ApiError>().code)

        // Anonymous congregation creation is 401.
        val anon = api.post("/congregations") { setBody(CreateCongregationRequest("X", "Y")) }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)
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
            asCoach(); setBody(CreateCongregationRequest("Flow Church", "Waco"))
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
            asCoach(); setBody(UpsertRosterEntryRequest("Timothy", 8, ShirtSize.YM))
        }.body()
        val entry = withMember.teams.single().members.single()
        assertEquals(8, entry.grade)
        assertEquals(8, entry.claimCode.length)
        assertEquals(8500, withMember.totalCents, "1 contestant × \$85")

        // Roster cap: 4 max.
        repeat(3) { i ->
            api.post("/registration/teams/$teamId/members") {
                asCoach(); setBody(UpsertRosterEntryRequest("Kid $i", 5, ShirtSize.YL))
            }
        }
        val fifth = api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Fifth", 6, ShirtSize.YL))
        }
        assertEquals(HttpStatusCode.Conflict, fifth.status)
        assertEquals("roster_full", fifth.body<ApiError>().code)

        // Bad grade is rejected.
        val badGrade = api.post("/registration/teams/$teamId/members") {
            asCoach(); setBody(UpsertRosterEntryRequest("Toddler", 1, ShirtSize.YS))
        }
        assertEquals(HttpStatusCode.BadRequest, badGrade.status)

        // Submit, then resume shows SUBMITTED with the full roster and total.
        val submitted: RegistrationDto = api.post("/registration/${congregation.id}/submit") { asCoach() }.body()
        assertEquals(RegistrationStatus.SUBMITTED, submitted.status)
        assertNotNull(submitted.submittedAt)
        assertEquals(4 * 8500, submitted.totalCents)

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
        assertEquals(HttpStatusCode.OK, api.post("/registration/${congregation.id}/submit") { asCoach() }.status)
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
            setBody(CreateCongregationRequest("Alpha Church", "Austin"))
        }.body()
        val teamA = api.post("/registration/${congA.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            setBody(UpsertTeamRequest("Alpha Team"))
        }.body<RegistrationDto>().teams.single()

        val bob = api.signUp("bob@tbb.org", "Bob")
        api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            setBody(CreateCongregationRequest("Beta Church", "Dallas"))
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
            setBody(UpsertRosterEntryRequest("Mole", 7, ShirtSize.AM))
        }
        assertEquals(HttpStatusCode.Forbidden, addMember.status)
        val submit = api.post("/registration/${congA.id}/submit") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, submit.status)

        // An admin passes the scope check everywhere.
        users.create("admin@tbb.org", "Admin", null, Passwords.hash("supersecret"), listOf(RoleGrant(Role.ADMIN)))
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
            setBody(CreateCongregationRequest("Late Church", "Waco"))
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
        users.create("admin@tbb.org", "Admin", null, Passwords.hash("supersecret"), listOf(RoleGrant(Role.ADMIN)))
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
}
