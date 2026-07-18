package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
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
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RegistrationDeskResponse
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.SetPaidRequest
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AdminRegistrationRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val openSeason = DEFAULT_SEASON.copy(
        priceContestantCents = 8500,
        registrationOpensOn = "2000-01-01",
        registrationClosesOn = "2999-12-31",
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private suspend fun HttpClient.signUp(email: String, name: String, adult: Boolean = true): AuthResponse =
        json.decodeFromString(
            post("/auth/register") {
                setBody(RegisterRequest(email, "password123", name, birthdate = "2013-05-01".takeUnless { adult }, adult = adult))
            }.bodyAsText()
        )

    private suspend fun HttpClient.loginSeededAdmin(users: UserRepository): AuthResponse {
        users.create(
            "admin@tbb.org", "Admin", null, adult = true,
            passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)),
        )
        return json.decodeFromString(
            post("/auth/login") { setBody(LoginRequest("admin@tbb.org", "supersecret")) }.bodyAsText()
        )
    }

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

    private fun congregationRequest(name: String, city: String) = CreateCongregationRequest(
        name = name, city = city, state = "TX", mailingAddress = "123 Main St", zip = "78701",
    )

    /** Signs up a coach and creates a congregation with one team of [memberCount] members. */
    private suspend fun HttpClient.coachWithCongregation(
        email: String, congregation: String, memberCount: Int = 0,
    ): Pair<AuthResponse, CongregationDto> {
        val coach = signUp(email, "Coach of $congregation")
        val cong: CongregationDto = post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(congregationRequest(congregation, "Austin"))
        }.body()
        if (memberCount > 0) {
            // Team mutations respond with the full updated RegistrationDto.
            val reg: RegistrationDto = post("/registration/${cong.id}/teams") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
                setBody(UpsertTeamRequest("Team A"))
            }.body()
            repeat(memberCount) { i ->
                post("/registration/teams/${reg.teams.single().id}/members") {
                    header(HttpHeaders.Authorization, "Bearer ${coach.token}")
                    setBody(
                        UpsertRosterEntryRequest("Kid $i", birthdate = "2013-05-01", shirtSize = ShirtSize.AM, gender = Gender.MALE)
                    )
                }
            }
        }
        return coach to cong
    }

    @Test
    fun deskRequiresAnEventWideGrant() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, api.get("/admin/registrations").status)

        val contestant = api.signUp("kid@tbb.org", "Kid")
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/registrations") {
                header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            }.status,
        )

        // The load-bearing case: a coach holds REGISTRATION_MANAGE, but congregation-scoped only.
        val (coach, _) = api.coachWithCongregation("coach@tbb.org", "First Church")
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/registrations") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            }.status,
        )

        // An EVENT-scoped REGISTRAR and a GLOBAL ADMIN both pass.
        val registrar = api.signUp("registrar@tbb.org", "Reg Istrar")
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/registrations") {
                header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
            }.status,
        )
        val admin = api.loginSeededAdmin(users)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/registrations") {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            }.status,
        )
    }

    @Test
    fun deskListsEveryCongregationWithTotalsAndCoaches() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val (coachA, congA) = api.coachWithCongregation("a@tbb.org", "Alpha Church", memberCount = 3)
        api.post("/registration/${congA.id}/submit") {
            header(HttpHeaders.Authorization, "Bearer ${coachA.token}")
        }
        // Beta Church exists but never starts a registration.
        val (_, congB) = api.coachWithCongregation("b@tbb.org", "Beta Church")

        val admin = api.loginSeededAdmin(users)
        val desk: RegistrationDeskResponse = api.get("/admin/registrations") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body()

        assertEquals(openSeason.eventYear, desk.seasonYear)
        assertEquals(listOf("Alpha Church", "Beta Church"), desk.rows.map { it.congregation.name })

        val alpha = desk.rows.single { it.congregation.id == congA.id }
        val alphaReg = assertNotNull(alpha.registration)
        assertEquals(RegistrationStatus.SUBMITTED, alphaReg.status)
        assertNotNull(alphaReg.submittedAt)
        assertEquals(3, alphaReg.teams.single().members.size)
        assertEquals(3 * 8500, alphaReg.totalCents)
        assertEquals(listOf("a@tbb.org"), alpha.coaches.map { it.email })

        val beta = desk.rows.single { it.congregation.id == congB.id }
        assertNull(beta.registration, "no registration started for Beta Church")
        assertEquals(listOf("b@tbb.org"), beta.coaches.map { it.email })
    }

    @Test
    fun paidToggleSetsAndClearsAndIsEventWideGated() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val (coach, cong) = api.coachWithCongregation("coach@tbb.org", "Paid Church", memberCount = 1)
        val admin = api.loginSeededAdmin(users)
        val desk: RegistrationDeskResponse = api.get("/admin/registrations") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body()
        val regId = assertNotNull(desk.rows.single { it.congregation.id == cong.id }.registration).id

        // The coach's congregation-scoped grant isn't enough, even for their own registration.
        assertEquals(
            HttpStatusCode.Forbidden,
            api.put("/admin/registrations/$regId/paid") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
                setBody(SetPaidRequest(paid = true))
            }.status,
        )

        val paid: RegistrationDto = api.put("/admin/registrations/$regId/paid") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(SetPaidRequest(paid = true))
        }.body()
        assertNotNull(paid.paidAt)
        assertEquals(8500, paid.totalCents)

        // Reflected on the desk, then clearable again.
        val refreshed: RegistrationDeskResponse = api.get("/admin/registrations") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body()
        assertNotNull(refreshed.rows.single { it.congregation.id == cong.id }.registration?.paidAt)

        val cleared: RegistrationDto = api.put("/admin/registrations/$regId/paid") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(SetPaidRequest(paid = false))
        }.body()
        assertNull(cleared.paidAt)

        assertEquals(
            HttpStatusCode.NotFound,
            api.put("/admin/registrations/no-such-reg/paid") {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                setBody(SetPaidRequest(paid = true))
            }.status,
        )
    }
}
