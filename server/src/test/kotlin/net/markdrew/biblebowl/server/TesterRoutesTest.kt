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
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.AssignMemberTeamRequest
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.SetRegistrationSiteRequest
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TesterListResponse
import net.markdrew.biblebowl.api.UpsertGuestRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TesterRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val openSeason = DEFAULT_SEASON.copy(
        registrationOpensOn = "2000-01-01",
        registrationClosesOn = "2999-12-31",
        registrationEnabled = true,
    )

    // Season 2027 → grade cutoff 2026-09-01: born 2013 = grade 8 (Junior), born 2017 = grade 4
    // (Elementary).
    private val juniorBirthdate = "2013-05-01"
    private val elementaryBirthdate = "2017-05-01"

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
        defaultRequest { contentType(ContentType.Application.Json) }
    }

    private suspend inline fun <reified T> HttpResponse.body(): T = json.decodeFromString<T>(bodyAsText())

    private suspend fun HttpClient.signUp(email: String, name: String, adult: Boolean = true): AuthResponse =
        json.decodeFromString(
            post("/auth/register") {
                setBody(
                    RegisterRequest(
                        email, "password123", name,
                        birthdate = juniorBirthdate.takeUnless { adult }, adult = adult,
                    )
                )
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

    /** Coach + congregation (with a two-letter [code]) holding one "Team A" of [teamBirthdates]. */
    private suspend fun HttpClient.coachWithTeam(
        email: String,
        congregation: String,
        code: String,
        teamBirthdates: List<String>,
        individualName: String? = null,
    ): Triple<AuthResponse, CongregationDto, RegistrationDto> {
        val coach = signUp(email, "Coach of $congregation")
        val cong: CongregationDto = post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(
                CreateCongregationRequest(
                    congregation, "Austin", state = "TX", mailingAddress = "123 Main St", zip = "78701",
                    code = code,
                )
            )
        }.body()
        var reg: RegistrationDto = post("/registration/${cong.id}/teams") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(UpsertTeamRequest("Team A"))
        }.body()
        teamBirthdates.forEachIndexed { i, birthdate ->
            reg = post("/registration/teams/${reg.teams.single().id}/members") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
                setBody(
                    UpsertRosterEntryRequest(
                        "Kid $i of $congregation", birthdate = birthdate,
                        shirtSize = ShirtSize.YL, gender = Gender.FEMALE,
                    )
                )
            }.body()
        }
        if (individualName != null) {
            reg = post("/registration/${cong.id}/individuals") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
                setBody(UpsertIndividualRequest(individualName, ShirtSize.AL, Gender.MALE))
            }.body()
        }
        return Triple(coach, cong, reg)
    }

    private suspend fun HttpClient.testers(token: String): TesterListResponse =
        get("/admin/testers") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

    @Test
    fun testersRequireAnEventWideRegistrarOrGraderGrant() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, api.get("/admin/testers").status)

        // A coach's congregation-scoped REGISTRATION_MANAGE doesn't reach this cross-congregation view.
        val (coach, _, _) = api.coachWithTeam("coach@tbb.org", "First Church", "FC", listOf(juniorBirthdate))
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/testers") { header(HttpHeaders.Authorization, "Bearer ${coach.token}") }.status,
        )

        // Registrar (REGISTRATION_MANAGE) and grader (SCORE_ENTER — ZipGrade is grading kit) both pass.
        val registrar = api.signUp("registrar@tbb.org", "Reggie")
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/testers") { header(HttpHeaders.Authorization, "Bearer ${registrar.token}") }.status,
        )
        val grader = api.signUp("grader@tbb.org", "Grady")
        users.addRoleGrant(grader.user.id, RoleGrant(Role.GRADER))
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/testers") { header(HttpHeaders.Authorization, "Bearer ${grader.token}") }.status,
        )
    }

    @Test
    fun assignsSequentialIdsAndWorkbookShapedExternalIds() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        // Junior + elementary on Team A (plays as Junior), an unassigned elementary, an adult.
        val (coach, _, reg) = api.coachWithTeam(
            "coach@tbb.org", "First Church", "FC",
            teamBirthdates = listOf(juniorBirthdate, elementaryBirthdate, elementaryBirthdate),
            individualName = "Deacon Dan",
        )
        val soloKid = reg.teams.single().members.last()
        api.put("/registration/members/${soloKid.id}/team") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(AssignMemberTeamRequest(null))
        }

        val admin = api.loginSeededAdmin(users)
        val testers = api.testers(admin.token)

        assertEquals(openSeason.eventYear, testers.seasonYear)
        assertEquals(4, testers.rows.size)
        // A zero-site season numbers everyone as one implicit site, from 1, in desk order
        // (congregation, team — teamless last, name).
        assertEquals(listOf(1, 2, 3, 4), testers.rows.map { it.testerId })
        val (junior, elemOnTeam) = testers.rows.filter { it.teamName == "Team A" }
            .sortedBy { it.division }.let { it[1] to it[0] }
        // Roster entries default to experienced; the team bracket is the highest member's
        // division floored at Junior, so the elementary member's team part still reads JE….
        assertEquals("JE-FC-JETEAM_A-${junior.testerId}", junior.externalId)
        assertEquals(Division.JUNIOR, junior.division)
        assertEquals("EE-FC-JETEAM_A-${elemOnTeam.testerId}", elemOnTeam.externalId)
        val solo = testers.rows.single { it.teamName == null && it.division == Division.ELEMENTARY }
        assertEquals("EE-FC-ENT-${solo.testerId}", solo.externalId)
        val adult = testers.rows.single { it.division == Division.ADULT }
        assertEquals("AD-FC-ANT-${adult.testerId}", adult.externalId)
        assertEquals("Deacon Dan", adult.name)
    }

    @Test
    fun assignedIdsAreStableAndNewTestersAppend() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val (coach, _, reg) = api.coachWithTeam(
            "coach@tbb.org", "First Church", "FC", teamBirthdates = listOf(juniorBirthdate),
        )
        val admin = api.loginSeededAdmin(users)
        val first = api.testers(admin.token)
        val originalIds = first.rows.associate { it.name to it.testerId }

        // "Aaron" sorts before "Kid 0…", but numbering is append-only: he gets the NEXT number.
        api.post("/registration/teams/${reg.teams.single().id}/members") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(
                UpsertRosterEntryRequest(
                    "Aaron Alphabetically-First", birthdate = juniorBirthdate,
                    shirtSize = ShirtSize.YM, gender = Gender.MALE,
                )
            )
        }
        val second = api.testers(admin.token)
        originalIds.forEach { (name, id) ->
            assertEquals(id, second.rows.single { it.name == name }.testerId, "id for $name changed")
        }
        assertEquals(
            (originalIds.values.filterNotNull().max()) + 1,
            second.rows.single { it.name.startsWith("Aaron") }.testerId,
        )
    }

    @Test
    fun multiSiteSeasonsNumberEachSiteInItsOwnBlock() = testApplication {
        val users = InMemoryUserRepository()
        val season = openSeason.copy(
            sites = listOf(EventSiteDto("s1", "Bandina"), EventSiteDto("s2", "White River")),
        )
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season))
        }
        val api = jsonClient()

        val (coach1, cong1, _) = api.coachWithTeam(
            "one@tbb.org", "Bandina Church", "BA", listOf(juniorBirthdate, juniorBirthdate),
        )
        api.put("/registration/${cong1.id}/site") {
            header(HttpHeaders.Authorization, "Bearer ${coach1.token}")
            setBody(SetRegistrationSiteRequest("s1"))
        }
        val (coach2, cong2, _) = api.coachWithTeam(
            "two@tbb.org", "River Church", "RC", listOf(juniorBirthdate),
        )
        api.put("/registration/${cong2.id}/site") {
            header(HttpHeaders.Authorization, "Bearer ${coach2.token}")
            setBody(SetRegistrationSiteRequest("s2"))
        }
        // A third congregation that never picked a site — its testers can't be numbered yet.
        api.coachWithTeam("three@tbb.org", "Undecided Church", "UN", listOf(juniorBirthdate))

        val admin = api.loginSeededAdmin(users)
        val testers = api.testers(admin.token)

        // Site 1 numbers from 1, site 2 from TESTER_ID_SITE_BLOCK + 1 = 201, like 2026.
        assertEquals(listOf(1, 2), testers.rows.filter { it.siteName == "Bandina" }.map { it.testerId })
        assertEquals(listOf(201), testers.rows.filter { it.siteName == "White River" }.map { it.testerId })
        val unpinned = testers.rows.single { it.congregationName == "Undecided Church" }
        assertNull(unpinned.testerId)
        assertNull(unpinned.externalId)
        assertNull(unpinned.siteId)
    }

    @Test
    fun nametagsPdfAssignsTesterIdsAndIsRegistrarGated() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        val (coach, cong, _) = api.coachWithTeam(
            "coach@tbb.org", "Tag Church", "TC", listOf(juniorBirthdate, elementaryBirthdate),
        )
        // A guest gets a nametag too — but never a tester ID.
        api.post("/registration/${cong.id}/guests") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(UpsertGuestRequest("Helpful Aunt", ShirtSize.AM, gender = Gender.FEMALE))
        }

        // The coach's congregation-scoped grant isn't enough, and neither is SCORE_ENTER alone —
        // nametags are a registrar artifact, unlike the tester list graders also need.
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/registrations/nametags.pdf") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            }.status,
        )
        val grader = api.signUp("grader@tbb.org", "Grader")
        users.addRoleGrant(grader.user.id, RoleGrant(Role.GRADER))
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/registrations/nametags.pdf") {
                header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            }.status,
        )

        if (!TypstCompiler.isAvailable) { println("typst not on PATH — skipping the compile half"); return@testApplication }

        val admin = api.loginSeededAdmin(users)
        val response = api.get("/admin/registrations/nametags.pdf") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("%PDF", response.readRawBytes().decodeToString(0, 4), "responds with a real PDF")

        // Generating assigned tester IDs through the same append-only scheme /admin/testers uses.
        val testers = api.testers(admin.token)
        assertEquals(listOf(1, 2), testers.rows.map { it.testerId })
    }
}
