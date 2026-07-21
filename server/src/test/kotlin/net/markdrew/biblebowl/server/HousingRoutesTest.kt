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
import net.markdrew.biblebowl.api.AddCabinAssignmentRequest
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.HousingResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.SetCheckoutDutyRequest
import net.markdrew.biblebowl.api.UpsertCabinRequest
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HousingRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val openSeason = DEFAULT_SEASON.copy(
        registrationOpensOn = "2000-01-01",
        registrationClosesOn = "2999-12-31",
        registrationEnabled = true,
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private suspend fun HttpClient.signUp(email: String, name: String): AuthResponse =
        json.decodeFromString(
            post("/auth/register") {
                setBody(RegisterRequest(email, "password123", name, adult = true))
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

    /** Signs up a coach and creates a congregation (which also starts a draft registration on use). */
    private suspend fun HttpClient.coachWithCongregation(
        email: String, congregation: String,
    ): Pair<AuthResponse, CongregationDto> {
        val coach = signUp(email, "Coach of $congregation")
        val cong: CongregationDto = post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(
                CreateCongregationRequest(
                    name = congregation, city = "Austin", state = "TX",
                    mailingAddress = "123 Main St", zip = "78701",
                )
            )
        }.body()
        return coach to cong
    }

    @Test
    fun housingRequiresAnEventWideGrant() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, api.get("/admin/housing").status)

        // A coach's congregation-scoped REGISTRATION_MANAGE doesn't qualify.
        val (coach, _) = api.coachWithCongregation("coach@tbb.org", "First Church")
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/housing") { header(HttpHeaders.Authorization, "Bearer ${coach.token}") }.status,
        )

        // An EVENT-scoped REGISTRAR passes.
        val registrar = api.signUp("registrar@tbb.org", "Reg Istrar")
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        val housing: HousingResponse = api.get("/admin/housing") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
        }.body()
        assertEquals(openSeason.eventYear, housing.seasonYear)
        assertTrue(housing.cabins.isEmpty())
    }

    @Test
    fun housingIsDarkWhileRegistrationIsDisabled() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason.copy(registrationEnabled = false)))
        }
        val api = jsonClient()

        val registrar = api.signUp("registrar@tbb.org", "Reg Istrar")
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        val response = api.get("/admin/housing") { header(HttpHeaders.Authorization, "Bearer ${registrar.token}") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals("feature_disabled", response.body<ApiError>().code)

        // Global admins bypass the dark-feature gate (testing in prod).
        val admin = api.loginSeededAdmin(users)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/housing") { header(HttpHeaders.Authorization, "Bearer ${admin.token}") }.status,
        )
    }

    @Test
    fun cabinAndAssignmentLifecycle() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val (_, cong) = api.coachWithCongregation("coach@tbb.org", "First Church")
        val admin = api.loginSeededAdmin(users)
        fun io.ktor.client.request.HttpRequestBuilder.asAdmin() =
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")

        // Add a cabin.
        var housing: HousingResponse = api.post("/admin/housing/cabins") {
            asAdmin(); setBody(UpsertCabinRequest("Bluebonnet", capacity = 12))
        }.body()
        val cabin = housing.cabins.single()
        assertEquals("Bluebonnet", cabin.name)
        assertEquals(12, cabin.capacity)
        assertNull(cabin.siteId)

        // A blank name and a duplicate name (case-insensitive) are rejected.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.post("/admin/housing/cabins") { asAdmin(); setBody(UpsertCabinRequest("  ")) }.status,
        )
        val dupe = api.post("/admin/housing/cabins") { asAdmin(); setBody(UpsertCabinRequest("bluebonnet")) }
        assertEquals(HttpStatusCode.Conflict, dupe.status)
        assertEquals("cabin_name_taken", dupe.body<ApiError>().code)

        // Rename and re-size it.
        housing = api.put("/admin/housing/cabins/${cabin.id}") {
            asAdmin(); setBody(UpsertCabinRequest("Bluebonnet Lodge", capacity = 10))
        }.body()
        assertEquals("Bluebonnet Lodge", housing.cabins.single().name)
        assertEquals(10, housing.cabins.single().capacity)

        // Assignment rows: a congregation × gender group, then an ad-hoc label row.
        housing = api.post("/admin/housing/cabins/${cabin.id}/assignments") {
            asAdmin(); setBody(AddCabinAssignmentRequest(congregationId = cong.id, gender = Gender.MALE))
        }.body()
        housing = api.post("/admin/housing/cabins/${cabin.id}/assignments") {
            asAdmin(); setBody(AddCabinAssignmentRequest(label = "Smith family — RV 3"))
        }.body()
        val assignments = housing.cabins.single().assignments
        assertEquals(2, assignments.size)
        assertEquals("First Church", assignments[0].congregationName)
        assertEquals(Gender.MALE, assignments[0].gender)
        assertEquals("Smith family — RV 3", assignments[1].label)
        assertNull(assignments[1].congregationId)

        // An empty assignment (no congregation, no label) is rejected.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.post("/admin/housing/cabins/${cabin.id}/assignments") {
                asAdmin(); setBody(AddCabinAssignmentRequest())
            }.status,
        )

        // Removing one row leaves the other.
        housing = api.delete("/admin/housing/assignments/${assignments[0].id}") { asAdmin() }.body()
        assertEquals(listOf("Smith family — RV 3"), housing.cabins.single().assignments.map { it.label })

        // Deleting the cabin removes it and its remaining assignments.
        housing = api.delete("/admin/housing/cabins/${cabin.id}") { asAdmin() }.body()
        assertTrue(housing.cabins.isEmpty())
        assertEquals(
            HttpStatusCode.NotFound,
            api.delete("/admin/housing/cabins/${cabin.id}") { asAdmin() }.status,
        )
    }

    @Test
    fun multiSiteCabinsRequireAValidSite() = testApplication {
        val users = InMemoryUserRepository()
        val bandina = EventSiteDto("bandina", "Bandina")
        val whiteRiver = EventSiteDto("white-river", "White River Youth Camp")
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason.copy(sites = listOf(bandina, whiteRiver))))
        }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)
        fun io.ktor.client.request.HttpRequestBuilder.asAdmin() =
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")

        // No site (or an unknown site) is rejected on a multi-site season.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.post("/admin/housing/cabins") { asAdmin(); setBody(UpsertCabinRequest("Cabin 1")) }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            api.post("/admin/housing/cabins") {
                asAdmin(); setBody(UpsertCabinRequest("Cabin 1", siteId = "nope"))
            }.status,
        )

        // The same cabin name is fine at two different sites.
        api.post("/admin/housing/cabins") {
            asAdmin(); setBody(UpsertCabinRequest("Cabin 1", siteId = bandina.id))
        }
        val housing: HousingResponse = api.post("/admin/housing/cabins") {
            asAdmin(); setBody(UpsertCabinRequest("Cabin 1", siteId = whiteRiver.id))
        }.body()
        assertEquals(listOf(bandina.id, whiteRiver.id), housing.cabins.map { it.siteId })
    }

    @Test
    fun checkoutDutyUpsertsAndClears() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val (_, cong) = api.coachWithCongregation("coach@tbb.org", "First Church")
        val admin = api.loginSeededAdmin(users)
        fun io.ktor.client.request.HttpRequestBuilder.asAdmin() =
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")

        var housing: HousingResponse = api.put("/admin/housing/checkout/${cong.id}") {
            asAdmin(); setBody(SetCheckoutDutyRequest("Jane Smith"))
        }.body()
        assertEquals(listOf("Jane Smith"), housing.duties.map { it.adultName })
        assertEquals("First Church", housing.duties.single().congregationName)

        // Re-setting replaces; blank clears; an unknown congregation is a 404.
        housing = api.put("/admin/housing/checkout/${cong.id}") {
            asAdmin(); setBody(SetCheckoutDutyRequest("John Doe"))
        }.body()
        assertEquals(listOf("John Doe"), housing.duties.map { it.adultName })
        housing = api.put("/admin/housing/checkout/${cong.id}") {
            asAdmin(); setBody(SetCheckoutDutyRequest("  "))
        }.body()
        assertTrue(housing.duties.isEmpty())
        assertEquals(
            HttpStatusCode.NotFound,
            api.put("/admin/housing/checkout/nope") { asAdmin(); setBody(SetCheckoutDutyRequest("X")) }.status,
        )
    }
}
