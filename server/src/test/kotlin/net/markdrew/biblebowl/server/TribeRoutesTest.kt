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
import net.markdrew.biblebowl.api.AddTribeLeaderRequest
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.TribesResponse
import net.markdrew.biblebowl.api.UpsertTribeRequest
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TribeRoutesTest {

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

    private suspend fun HttpClient.registrar(users: UserRepository): AuthResponse {
        val auth = signUp("registrar@tbb.org", "Reg Istrar")
        users.addRoleGrant(auth.user.id, RoleGrant(Role.REGISTRAR))
        return auth
    }

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

    @Test
    fun tribesRequireAnEventWideGrantAndTheFeatureToggle() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason.copy(registrationEnabled = false)))
        }
        val api = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, api.get("/admin/tribes").status)

        // Dark feature: even a registrar gets feature_disabled while the toggle is off…
        val registrar = api.registrar(users)
        val dark = api.get("/admin/tribes") { header(HttpHeaders.Authorization, "Bearer ${registrar.token}") }
        assertEquals(HttpStatusCode.Forbidden, dark.status)
        assertEquals("feature_disabled", dark.body<ApiError>().code)

        // …while a global admin bypasses the gate (testing in prod).
        val admin = api.loginSeededAdmin(users)
        val tribes: TribesResponse = api.get("/admin/tribes") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body()
        assertEquals(openSeason.eventYear.toString(), tribes.seasonYear)
        assertTrue(tribes.tribes.isEmpty())
    }

    @Test
    fun tribeLifecycleAddRenameLeadersDelete() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val registrar = api.registrar(users)
        suspend fun postTribe(req: UpsertTribeRequest): HttpResponse = api.post("/admin/tribes") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}"); setBody(req)
        }
        suspend fun postLeader(tribeId: String, req: AddTribeLeaderRequest): HttpResponse =
            api.post("/admin/tribes/$tribeId/leaders") {
                header(HttpHeaders.Authorization, "Bearer ${registrar.token}"); setBody(req)
            }

        // Add two tribes; duplicate names (case-insensitive) at the same site conflict.
        postTribe(UpsertTribeRequest("Red"))
        var tribes: TribesResponse = postTribe(UpsertTribeRequest("Turquoise")).body()
        assertEquals(listOf("Red", "Turquoise"), tribes.tribes.map { it.name })
        assertEquals(HttpStatusCode.Conflict, postTribe(UpsertTribeRequest("red")).status)
        assertEquals(HttpStatusCode.BadRequest, postTribe(UpsertTribeRequest("  ")).status)

        // Two leaders each, the 2026 pattern; blank leader names are rejected.
        val red = tribes.tribes.first { it.name == "Red" }
        postLeader(red.id, AddTribeLeaderRequest("Kisha Dearlove"))
        tribes = postLeader(red.id, AddTribeLeaderRequest("Taylor Jones")).body()
        assertEquals(
            listOf("Kisha Dearlove", "Taylor Jones"),
            tribes.tribes.first { it.name == "Red" }.leaders.map { it.name },
        )
        assertEquals(HttpStatusCode.BadRequest, postLeader(red.id, AddTribeLeaderRequest(" ")).status)

        // Rename sticks; removing a leader and deleting a tribe (leaders and all) works.
        tribes = api.put("/admin/tribes/${red.id}") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
            setBody(UpsertTribeRequest("Red and Yellow Swirl"))
        }.body()
        val renamed = tribes.tribes.first { it.id == red.id }
        assertEquals("Red and Yellow Swirl", renamed.name)
        assertEquals(2, renamed.leaders.size, "rename keeps the assigned leaders")

        tribes = api.delete("/admin/tribes/leaders/${renamed.leaders.first().id}") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
        }.body()
        assertEquals(listOf("Taylor Jones"), tribes.tribes.first { it.id == red.id }.leaders.map { it.name })

        tribes = api.delete("/admin/tribes/${red.id}") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
        }.body()
        assertEquals(listOf("Turquoise"), tribes.tribes.map { it.name })
    }

    @Test
    fun multiSiteSeasonsRequireASitePerTribe() = testApplication {
        val users = InMemoryUserRepository()
        val season = openSeason.copy(
            sites = listOf(EventSiteDto("s1", "Bandina"), EventSiteDto("s2", "White River")),
        )
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season))
        }
        val api = jsonClient()
        val registrar = api.registrar(users)
        suspend fun post(body: UpsertTribeRequest): HttpResponse = api.post("/admin/tribes") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}"); setBody(body)
        }

        // No site (or a stale site id) is rejected on a multi-site season.
        assertEquals(HttpStatusCode.BadRequest, post(UpsertTribeRequest("Red")).status)
        assertEquals(HttpStatusCode.BadRequest, post(UpsertTribeRequest("Red", siteId = "gone")).status)

        // The same name may exist at each site; tribes list site-first.
        post(UpsertTribeRequest("Red", siteId = "s1"))
        val tribes: TribesResponse = post(UpsertTribeRequest("Red", siteId = "s2")).body()
        assertEquals(listOf("s1" to "Red", "s2" to "Red"), tribes.tribes.map { it.siteId to it.name })
    }
}
