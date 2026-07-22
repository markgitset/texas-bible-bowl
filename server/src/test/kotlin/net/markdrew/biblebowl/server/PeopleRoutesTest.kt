package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ClaimPersonRequest
import net.markdrew.biblebowl.api.ClaimPersonResponse
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.MyPeopleResponse
import net.markdrew.biblebowl.api.PeopleSearchResponse
import net.markdrew.biblebowl.api.PersonRelation
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.CreateCongregationResult
import net.markdrew.biblebowl.server.data.InMemoryCongregationRepository
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryRegistrationRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.security.JwtService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The person-centric claim + mine endpoints (schema redesign phase 4). */
class PeopleRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val season = DEFAULT_SEASON.copy(
        registrationOpensOn = "2000-01-01", registrationClosesOn = "2999-12-31", registrationEnabled = true,
    )

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString(bodyAsText())

    @Test
    fun claimAPersonManagesThemAndListsThemUnderMine() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season),
                congregations = congregations, registrations = registrations)
        }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        // A coach adds a youth contestant; the returned entry carries the person's claim code.
        val coach = users.create("coach@tbb.org", "Coach", null, adult = true,
            passwordHash = net.markdrew.biblebowl.server.security.Passwords.hash("password123"),
            roles = listOf(RoleGrant(Role.COACH)))
        val cong = (congregations.create(
            CreateCongregationRequest("Claim Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        ) as CreateCongregationResult.Created).congregation
        val team = assertNotNull(registrations.addTeam(cong.id, season.eventYear.toString(), "Team A"))
        val entry = (registrations.addMember(
            team.id, UpsertRosterEntryRequest("Timmy", birthdate = "2013-05-01", shirtSize = ShirtSize.YL, gender = Gender.MALE),
        ) as AddMemberResult.Added).entry

        // A parent account claims the child by code — a MANAGED relation (child, not the account).
        val parent: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("parent@tbb.org", "password123", "Parent", adult = true))
        }.body()
        val claim: ClaimPersonResponse = api.post("/people/claim") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
            setBody(ClaimPersonRequest(entry.claimCode))
        }.body()
        assertEquals(PersonRelation.MANAGED, claim.relation)
        assertEquals("Timmy", claim.person.name)

        // /people/mine lists the managed child with their participation.
        val mine: MyPeopleResponse = api.get("/people/mine") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }.body()
        val managed = mine.people.single()
        assertEquals("Timmy", managed.person.name)
        assertEquals(PersonRelation.MANAGED, managed.person.relation)
        assertEquals(listOf(season.eventYear.toString()), managed.participations.map { it.seasonYear })
        assertEquals("Team A", managed.participations.single().teamName)

        // A bogus code is a 404; a malformed one is a 400.
        assertEquals(HttpStatusCode.NotFound, api.post("/people/claim") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
            setBody(ClaimPersonRequest("ABCD2345"))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, api.post("/people/claim") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
            setBody(ClaimPersonRequest("xx"))
        }.status)

        // A different account can't steal an already-claimed person.
        val other: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("other@tbb.org", "password123", "Other", adult = true))
        }.body()
        assertEquals(HttpStatusCode.Conflict, api.post("/people/claim") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
            setBody(ClaimPersonRequest(entry.claimCode))
        }.status)
        assertTrue(api.get("/people/mine") {
            header(HttpHeaders.Authorization, "Bearer ${other.token}")
        }.body<MyPeopleResponse>().people.isEmpty())
    }

    @Test
    fun mergePeopleEndpointIsRegistrarGatedAndFoldsDuplicates() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season),
                congregations = congregations, registrations = registrations)
        }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        val coach = users.create("mrcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = net.markdrew.biblebowl.server.security.Passwords.hash("password123"),
            roles = listOf(RoleGrant(Role.COACH)))
        val cong = (congregations.create(
            CreateCongregationRequest("Merge Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        ) as CreateCongregationResult.Created).congregation
        val t26 = assertNotNull(registrations.addTeam(cong.id, season.eventYear.toString(), "Team A"))
        val sam = (registrations.addMember(
            t26.id, UpsertRosterEntryRequest("Sam", birthdate = "2013-05-01", shirtSize = ShirtSize.YM, gender = Gender.MALE),
        ) as AddMemberResult.Added).entry
        val t28 = assertNotNull(registrations.addTeam(cong.id, "2028", "Team A"))
        val samuel = (registrations.addMember(
            t28.id, UpsertRosterEntryRequest("Samuel", birthdate = "2013-05-02", shirtSize = ShirtSize.YM, gender = Gender.MALE),
        ) as AddMemberResult.Added).entry
        val keepId = assertNotNull(registrations.contestantIdForMember(sam.id))
        val mergeId = assertNotNull(registrations.contestantIdForMember(samuel.id))

        // A non-registrar account is refused.
        val parent: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("nreg@tbb.org", "password123", "Parent", adult = true))
        }.body()
        assertEquals(HttpStatusCode.Forbidden, api.post("/admin/people/merge") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
            setBody(net.markdrew.biblebowl.api.MergePeopleRequest(keepId, mergeId))
        }.status)

        // A registrar folds the two (different seasons — no overlap).
        val registrar: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("registrar@tbb.org", "password123", "Reg", adult = true))
        }.body()
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        val merged: net.markdrew.biblebowl.api.MergePeopleResponse = api.post("/admin/people/merge") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
            setBody(net.markdrew.biblebowl.api.MergePeopleRequest(keepId, mergeId))
        }.body()
        assertEquals(keepId, merged.person.person.id)
        assertEquals(setOf(season.eventYear.toString(), "2028"), merged.person.participations.map { it.seasonYear }.toSet())
    }

    @Test
    fun searchPeopleIsRegistrarGatedAndMatchesByName() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season),
                congregations = congregations, registrations = registrations)
        }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        val coach = users.create("srchcoach@tbb.org", "Coach", null, adult = true,
            passwordHash = net.markdrew.biblebowl.server.security.Passwords.hash("password123"),
            roles = listOf(RoleGrant(Role.COACH)))
        val cong = (congregations.create(
            CreateCongregationRequest("Search Church", "Waco", state = "TX", mailingAddress = "1 Main St", zip = "76701"),
            coach.id,
        ) as CreateCongregationResult.Created).congregation
        val team = assertNotNull(registrations.addTeam(cong.id, season.eventYear.toString(), "Team A"))
        listOf("Sam" to "2013-05-01", "Samuel" to "2013-05-02", "Bethany" to "2013-05-03").forEach { (n, b) ->
            registrations.addMember(team.id, UpsertRosterEntryRequest(n, birthdate = b, shirtSize = ShirtSize.YM, gender = Gender.MALE))
        }

        // A non-registrar account is refused.
        val parent: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("srchparent@tbb.org", "password123", "Parent", adult = true))
        }.body()
        assertEquals(HttpStatusCode.Forbidden, api.get("/admin/people?query=sam") {
            header(HttpHeaders.Authorization, "Bearer ${parent.token}")
        }.status)

        val registrar: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("srchreg@tbb.org", "password123", "Reg", adult = true))
        }.body()
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        fun search(q: String): List<String> = kotlinx.coroutines.runBlocking {
            api.get("/admin/people?query=$q") { header(HttpHeaders.Authorization, "Bearer ${registrar.token}") }
                .body<PeopleSearchResponse>().people.map { it.person.name }
        }
        // Case-insensitive substring match, name-sorted.
        assertEquals(listOf("Sam", "Samuel"), search("SAM"))
        // A blank query lists everyone.
        assertEquals(listOf("Bethany", "Sam", "Samuel"), search(""))
    }
}
