package net.markdrew.biblebowl.client

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.ScoreEntryDto
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.client.TbbApi
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the exact requests [TbbApi] puts on the wire — in particular that every customize
 * option reaches the query string. A tiny recording HTTP server stands in for the backend
 * (TbbApi builds its own engine, so a mock engine can't be injected).
 */
class TbbApiRequestTest {

    private val requests = mutableListOf<String>()
    private val methods = mutableListOf<String>()
    private val authHeaders = mutableListOf<String?>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += exchange.requestURI.toString()
            methods += exchange.requestMethod
            authHeaders += exchange.requestHeaders.getFirst("Authorization")
            val (body, contentType) = when (exchange.requestURI.path) {
                "/auth/login" ->
                    """{"token":"tok123","user":{"id":"u1","email":"admin@tbb.org","displayName":"Admin"}}""" to
                        "application/json"
                "/auth/me" ->
                    if (exchange.requestMethod == "PUT") {
                        """{"id":"u1","email":"admin@tbb.org","displayName":"Coach Carol","adult":true}""" to
                            "application/json"
                    } else {
                        """{"id":"u1","email":"admin@tbb.org","displayName":"Admin"}""" to "application/json"
                    }
                "/generate/cache" -> """{"cleared":3}""" to "application/json"
                "/congregations" ->
                    if (exchange.requestMethod == "POST") {
                        """{"id":"c1","name":"First Church","city":"Austin"}""" to "application/json"
                    } else {
                        """[{"id":"c1","name":"First Church","city":"Austin"}]""" to "application/json"
                    }
                "/congregations/c1" ->
                    """{"id":"c1","name":"First Christian Church","city":"Round Rock","state":"TX"}""" to "application/json"
                "/congregations/code-suggestion" -> """{"code":"WB"}""" to "application/json"
                "/registration/mine" ->
                    """{"congregations":[],"registration":null,"windowOpen":true}""" to "application/json"
                "/admin/registrations" ->
                    """{"seasonYear":"2027","rows":[]}""" to "application/json"
                "/admin/registrations/r1/paid" ->
                    """{"id":"r1","congregation":{"id":"c1","name":"First Church","city":"Austin"},
                        "seasonYear":"2027","status":"SUBMITTED","paidAt":"2027-01-15T00:00:00Z"}""" to
                        "application/json"
                "/users" ->
                    """[{"id":"u2","email":"carol@tbb.org","displayName":"Carol"}]""" to "application/json"
                "/users/u2/roles" ->
                    """{"id":"u2","email":"carol@tbb.org","displayName":"Carol"}""" to "application/json"
                "/roster/claim" ->
                    """{"id":"m1","name":"Timothy","shirtSize":"YM","claimCode":"ABCD2345","claimed":true}""" to
                        "application/json"
                "/admin/scores", "/admin/scores/release" ->
                    """{"seasonYear":"2027","rows":[]}""" to "application/json"
                "/admin/scores/standings" ->
                    """{"seasonYear":"2027","divisions":[]}""" to "application/json"
                "/scores/mine" ->
                    """{"seasonYear":"2027","released":false,"rows":[]}""" to "application/json"
                else ->
                    if (exchange.requestURI.path.startsWith("/registration/")) {
                        """{"id":"r1","congregation":{"id":"c1","name":"First Church","city":"Austin"},
                            "seasonYear":"2027","status":"DRAFT","teams":[]}""" to "application/json"
                    } else {
                        "%PDF-1.7 fake" to "application/pdf"
                    }
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val api = TbbApi("http://127.0.0.1:${server.address.port}")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun bibleTextPdfSendsEveryCustomizeOption() = runBlocking {
        api.bibleTextPdf(
            fontSize = 14,
            twoColumns = true,
            justified = true,
            chapterBreaksPage = true,
            highlight = false,
            underlineUniqueWords = true,
        )
        val uri = requests.single()
        assertTrue(uri.startsWith("/generate/bible-text.pdf?"), uri)
        listOf(
            "fontSize=14", "twoColumns=true", "justified=true",
            "chapterBreaksPage=true", "highlight=false", "underlineUniqueWords=true",
        ).forEach { param -> assertTrue(param in uri, "expected $param in $uri") }
    }

    @Test
    fun bibleTextPdfDefaultsSendNoOptions() = runBlocking {
        api.bibleTextPdf()
        assertEquals("/generate/bible-text.pdf", requests.single(), "defaults are the server's defaults")
    }

    @Test
    fun practiceTestPdfSendsLimitAndSeed() = runBlocking {
        api.practiceTestPdf(Round.FIND_THE_VERSE, chapter = 7, limit = 20, seed = 1234)
        val uri = requests.single()
        listOf("round=FIND_THE_VERSE", "chapter=7", "limit=20", "seed=1234").forEach { param ->
            assertTrue(param in uri, "expected $param in $uri")
        }
    }

    @Test
    fun registrationEndpointsSendAuthorizedTypedRequests() = runBlocking {
        api.login(LoginRequest("coach@tbb.org", "password123"))

        api.createCongregation(CreateCongregationRequest("First Church", "Austin"))
        assertEquals("POST" to "/congregations", methods.last() to requests.last())

        api.searchCongregations("first")
        assertEquals("GET", methods.last())
        assertTrue(requests.last().startsWith("/congregations?query=first"), requests.last())

        api.updateCongregation("c1", UpdateCongregationRequest("First Christian Church", "Round Rock", state = "TX", mailingAddress = "456 Oak Ave", zip = "78664", code = "FC"))
        assertEquals("PUT" to "/congregations/c1", methods.last() to requests.last())

        assertEquals("WB", api.suggestCongregationCode("West Bexar County Church of Christ"))
        assertEquals("GET", methods.last())
        assertTrue(requests.last().startsWith("/congregations/code-suggestion?name="), requests.last())

        val mine = api.myRegistration()
        assertTrue(mine.windowOpen)
        assertEquals("/registration/mine", requests.last())

        api.addTeam("c1", "Team Alpha")
        assertEquals("POST" to "/registration/c1/teams", methods.last() to requests.last())

        api.renameTeam("t1", "Team Beta")
        assertEquals("PUT" to "/registration/teams/t1", methods.last() to requests.last())

        api.deleteTeam("t1")
        assertEquals("DELETE" to "/registration/teams/t1", methods.last() to requests.last())

        api.addRosterEntry("t1", UpsertRosterEntryRequest("Timothy", "2013-05-01", ShirtSize.YM, Gender.MALE, inexperienced = true))
        assertEquals("POST" to "/registration/teams/t1/members", methods.last() to requests.last())

        api.updateRosterEntry("m1", UpsertRosterEntryRequest("Tim", "2012-05-01", ShirtSize.YL, Gender.MALE))
        assertEquals("PUT" to "/registration/members/m1", methods.last() to requests.last())

        api.deleteRosterEntry("m1")
        assertEquals("DELETE" to "/registration/members/m1", methods.last() to requests.last())

        api.assignMemberTeam("m1", "t2")
        assertEquals("PUT" to "/registration/members/m1/team", methods.last() to requests.last())

        api.assignMemberTeam("m1", null) // unassign
        assertEquals("PUT" to "/registration/members/m1/team", methods.last() to requests.last())

        api.enrollContestant("c1", "ct1", ShirtSize.YM, "t2")
        assertEquals("POST" to "/registration/c1/contestants/ct1/enroll", methods.last() to requests.last())

        api.addIndividual("c1", UpsertIndividualRequest("Pat Adult", ShirtSize.AXL, Gender.FEMALE))
        assertEquals("POST" to "/registration/c1/individuals", methods.last() to requests.last())

        api.updateIndividual("i1", UpsertIndividualRequest("Pat A.", ShirtSize.AM, Gender.FEMALE))
        assertEquals("PUT" to "/registration/individuals/i1", methods.last() to requests.last())

        api.deleteIndividual("i1")
        assertEquals("DELETE" to "/registration/individuals/i1", methods.last() to requests.last())

        api.addGuest("c1", UpsertGuestRequest("Aunt Vol", ShirtSize.AM, birthdate = null, gender = Gender.FEMALE))
        assertEquals("POST" to "/registration/c1/guests", methods.last() to requests.last())

        api.updateGuest("g1", UpsertGuestRequest("Aunt V.", ShirtSize.AL, birthdate = "2020-06-15", gender = Gender.FEMALE))
        assertEquals("PUT" to "/registration/guests/g1", methods.last() to requests.last())

        api.deleteGuest("g1")
        assertEquals("DELETE" to "/registration/guests/g1", methods.last() to requests.last())

        api.submitRegistration("c1")
        assertEquals("POST" to "/registration/c1/submit", methods.last() to requests.last())

        api.refreshUser()
        assertEquals("/auth/me", requests.last())

        api.updateProfile(UpdateProfileRequest("Coach Carol", adult = true))
        assertEquals("PUT" to "/auth/me", methods.last() to requests.last())
        assertEquals("Coach Carol", api.user?.displayName, "updateProfile refreshes the cached user")

        // Every registration call must carry the signed-in token.
        assertTrue(authHeaders.drop(1).all { it == "Bearer tok123" }, "missing Bearer on some call: $authHeaders")
    }

    @Test
    fun adminEndpointsSendAuthorizedTypedRequests() = runBlocking {
        api.login(LoginRequest("admin@tbb.org", "supersecret"))

        val desk = api.registrationDesk()
        assertEquals("2027", desk.seasonYear)
        assertEquals("GET" to "/admin/registrations", methods.last() to requests.last())

        val paid = api.setRegistrationPaid("r1", paid = true)
        assertEquals("2027-01-15T00:00:00Z", paid.paidAt)
        assertEquals("PUT" to "/admin/registrations/r1/paid", methods.last() to requests.last())

        api.searchUsers("carol")
        assertEquals("GET" to "/users?query=carol", methods.last() to requests.last())

        api.grantRole("u2", RoleGrant(Role.COACH, ScopeType.CONGREGATION, "c1"))
        assertEquals("POST" to "/users/u2/roles", methods.last() to requests.last())

        api.revokeRole("u2", RoleGrant(Role.COACH, ScopeType.CONGREGATION, "c1"))
        assertEquals("DELETE", methods.last())
        val revokeUri = requests.last()
        listOf("role=COACH", "scopeType=CONGREGATION", "scopeId=c1").forEach { param ->
            assertTrue(param in revokeUri, "expected $param in $revokeUri")
        }

        // A null scopeId stays out of the query string entirely.
        api.revokeRole("u2", RoleGrant(Role.ADMIN, ScopeType.GLOBAL, null))
        assertTrue("scopeId" !in requests.last(), requests.last())

        assertTrue(authHeaders.drop(1).all { it == "Bearer tok123" }, "missing Bearer on some call: $authHeaders")
    }

    @Test
    fun scoringEndpointsSendAuthorizedTypedRequests() = runBlocking {
        api.login(LoginRequest("grader@tbb.org", "password123"))

        val claimed = api.claimRosterEntry("abcd-2345")
        assertEquals("Timothy", claimed.name)
        assertTrue(claimed.claimed)
        assertEquals("POST" to "/roster/claim", methods.last() to requests.last())

        val sheet = api.gradingSheet()
        assertEquals("2027", sheet.seasonYear)
        assertEquals("GET" to "/admin/scores", methods.last() to requests.last())

        api.saveScores(
            listOf(
                ScoreEntryDto("m1", Round.FIND_THE_VERSE, 38),
                ScoreEntryDto("m1", Round.POWER, null), // a cleared cell rides in the same batch
            )
        )
        assertEquals("PUT" to "/admin/scores", methods.last() to requests.last())

        api.setScoresReleased(true)
        assertEquals("PUT" to "/admin/scores/release", methods.last() to requests.last())

        val tally = api.standings()
        assertEquals("2027", tally.seasonYear)
        assertEquals("GET" to "/admin/scores/standings", methods.last() to requests.last())

        val mine = api.myScores()
        assertEquals(false, mine.released)
        assertEquals("GET" to "/scores/mine", methods.last() to requests.last())

        assertTrue(authHeaders.drop(1).all { it == "Bearer tok123" }, "missing Bearer on some call: $authHeaders")
    }

    @Test
    fun clearPdfCacheSendsAuthorizedDelete() = runBlocking {
        api.login(LoginRequest("admin@tbb.org", "supersecret"))
        val res = api.clearPdfCache()
        assertEquals(3, res.cleared)
        assertEquals("DELETE", methods.last())
        assertEquals("/generate/cache", requests.last())
        assertEquals("Bearer tok123", authHeaders.last(), "the clear must carry the signed-in token")
    }
}
