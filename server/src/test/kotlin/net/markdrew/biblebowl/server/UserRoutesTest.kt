package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
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
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

    private suspend fun HttpClient.loginSeededAdmin(
        users: UserRepository, email: String = "admin@tbb.org",
    ): AuthResponse {
        users.create(
            email, "Admin", null, adult = true,
            passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)),
        )
        return json.decodeFromString(
            post("/auth/login") { setBody(LoginRequest(email, "supersecret")) }.bodyAsText()
        )
    }

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

    @Test
    fun userSearchIsAdminOnlyAndMatchesNameOrEmail() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = jsonClient()

        api.signUp("carol@tbb.org", "Carol Coach")
        api.signUp("dave@example.com", "Dave Driver")

        assertEquals(HttpStatusCode.Unauthorized, api.get("/users") { parameter("query", "carol") }.status)

        val contestant = api.signUp("kid@tbb.org", "Kid")
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/users") {
                header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
                parameter("query", "carol")
            }.status,
        )

        val admin = api.loginSeededAdmin(users)
        suspend fun search(q: String): List<UserDto> = api.get("/users") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            parameter("query", q)
        }.body()

        assertEquals(listOf("carol@tbb.org"), search("CAROL").map { it.email }, "case-insensitive name/email")
        assertEquals(listOf("dave@example.com"), search("driver").map { it.email }, "display-name match")
        assertEquals(3, search("tbb.org").size)
        assertEquals(emptyList(), search(""), "blank query never dumps the user table")
    }

    @Test
    fun grantValidatesScopeAndIsIdempotent() = testApplication {
        val users = InMemoryUserRepository()
        application {
            // Registration must be live for the coach's self-serve congregation creation below.
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(DEFAULT_SEASON.copy(registrationEnabled = true)))
        }
        val api = jsonClient()

        val coach = api.signUp("carol@tbb.org", "Carol Coach")
        val cong: CongregationDto = api.post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(CreateCongregationRequest("First Church", "Austin", "TX", "123 Main St", "78701"))
        }.body()
        val target = api.signUp("newcoach@tbb.org", "New Coach")
        val admin = api.loginSeededAdmin(users)

        suspend fun grant(userId: String, grant: RoleGrant): HttpResponse =
            api.post("/users/$userId/roles") {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                setBody(grant)
            }

        // Scope validation.
        val wrongScope = grant(target.user.id, RoleGrant(Role.COACH, ScopeType.GLOBAL))
        assertEquals(HttpStatusCode.BadRequest, wrongScope.status)
        assertEquals("invalid_scope", wrongScope.body<ApiError>().code)
        val noCong = grant(target.user.id, RoleGrant(Role.COACH, ScopeType.CONGREGATION, null))
        assertEquals("unknown_congregation", noCong.body<ApiError>().code)
        val badCong = grant(target.user.id, RoleGrant(Role.COACH, ScopeType.CONGREGATION, "nope"))
        assertEquals("unknown_congregation", badCong.body<ApiError>().code)
        val scopedGlobal = grant(target.user.id, RoleGrant(Role.ADMIN, ScopeType.GLOBAL, "nope"))
        assertEquals("invalid_scope", scopedGlobal.body<ApiError>().code)
        assertEquals(
            HttpStatusCode.NotFound,
            grant("no-such-user", RoleGrant(Role.COACH, ScopeType.CONGREGATION, cong.id)).status,
        )

        // The closing of the "contact us" loop: admin grants COACH for an existing congregation.
        val coachGrant = RoleGrant(Role.COACH, ScopeType.CONGREGATION, cong.id)
        val granted: UserDto = grant(target.user.id, coachGrant).body()
        assertTrue(coachGrant in granted.roles)
        // The manage-users UI labels the grant by congregation name, not UUID.
        assertEquals("First Church", granted.congregationNames[cong.id])

        val found: List<UserDto> = api.get("/users") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            parameter("query", "newcoach")
        }.body()
        assertEquals("First Church", found.single().congregationNames[cong.id], "search resolves names too")

        // The target's own session now carries the coach permissions.
        val me: UserDto = api.get("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${target.token}")
        }.body()
        assertTrue(Permission.TEAM_MANAGE in me.permissions)

        // Re-granting is a no-op, not a duplicate row.
        val repeated: UserDto = grant(target.user.id, coachGrant).body()
        assertEquals(1, repeated.roles.count { it == coachGrant })
    }

    @Test
    fun revokeRemovesGrantsButNeverYourOwnAdmin() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"))
        }
        val api = jsonClient()

        val target = api.signUp("carol@tbb.org", "Carol Coach")
        users.addRoleGrant(target.user.id, RoleGrant(Role.REGISTRAR))
        val admin = api.loginSeededAdmin(users)

        suspend fun revoke(userId: String, role: Role, scopeType: ScopeType, scopeId: String? = null): HttpResponse =
            api.delete("/users/$userId/roles") {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                parameter("role", role.name)
                parameter("scopeType", scopeType.name)
                scopeId?.let { parameter("scopeId", it) }
            }

        val revoked: UserDto = revoke(target.user.id, Role.REGISTRAR, ScopeType.EVENT).body()
        assertFalse(revoked.roles.any { it.role == Role.REGISTRAR })

        // Revoking a grant the user doesn't hold is a 404.
        val missing = revoke(target.user.id, Role.REGISTRAR, ScopeType.EVENT)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("grant_not_found", missing.body<ApiError>().code)

        // Garbage enums are a 400, unknown users a 404.
        val garbage = api.delete("/users/${target.user.id}/roles") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            parameter("role", "SUPERUSER")
            parameter("scopeType", "GLOBAL")
        }
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
        assertEquals(HttpStatusCode.NotFound, revoke("no-such-user", Role.ADMIN, ScopeType.GLOBAL).status)

        // The lockout guard: an admin can't revoke their own GLOBAL ADMIN grant...
        val self = revoke(admin.user.id, Role.ADMIN, ScopeType.GLOBAL)
        assertEquals(HttpStatusCode.Conflict, self.status)
        assertEquals("cannot_revoke_own_admin", self.body<ApiError>().code)
        val stillAdmin: UserDto = api.get("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body()
        assertTrue(stillAdmin.roles.any { it.role == Role.ADMIN })

        // ...but revoking another admin's grant works.
        val otherAdmin = api.loginSeededAdmin(users, email = "admin2@tbb.org")
        val demoted: UserDto = revoke(otherAdmin.user.id, Role.ADMIN, ScopeType.GLOBAL).body()
        assertFalse(demoted.roles.any { it.role == Role.ADMIN })
    }
}
