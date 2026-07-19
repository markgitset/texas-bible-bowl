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
import net.markdrew.biblebowl.api.ClaimEntryRequest
import net.markdrew.biblebowl.api.CongregationDto
import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Division
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.GradingSheetResponse
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.MyScoresResponse
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.RegistrationDto
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.RosterEntryDto
import net.markdrew.biblebowl.api.SaveScoresRequest
import net.markdrew.biblebowl.api.ScoreEntryDto
import net.markdrew.biblebowl.api.SetScoresReleasedRequest
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.api.UpsertTeamRequest
import net.markdrew.biblebowl.model.Round
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
import kotlin.test.assertTrue

class ScoreRoutesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val openSeason = DEFAULT_SEASON.copy(
        registrationOpensOn = "2000-01-01",
        registrationClosesOn = "2999-12-31",
    )

    // Season 2027 → grade cutoff 2026-09-01: born 2013 = grade 8 (Junior), born 2017 = grade 4 (Elementary).
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

    private suspend fun HttpClient.grader(users: UserRepository, email: String = "grader@tbb.org"): AuthResponse {
        val auth = signUp(email, "Grady Grader")
        users.addRoleGrant(auth.user.id, RoleGrant(Role.GRADER))
        return auth
    }

    /** Coach + congregation with one team ([teamBirthdates] members) and optionally an adult individual. */
    private suspend fun HttpClient.coachWithTeam(
        email: String,
        congregation: String,
        teamBirthdates: List<String>,
        individualName: String? = null,
    ): Triple<AuthResponse, CongregationDto, RegistrationDto> {
        val coach = signUp(email, "Coach of $congregation")
        val cong: CongregationDto = post("/congregations") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(
                CreateCongregationRequest(
                    congregation, "Austin", state = "TX", mailingAddress = "123 Main St", zip = "78701",
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

    @Test
    fun gradingDeskRequiresAnEventWideScoreEnterGrant() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, api.get("/admin/scores").status)

        val contestant = api.signUp("kid@tbb.org", "Kid", adult = false)
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/scores") { header(HttpHeaders.Authorization, "Bearer ${contestant.token}") }.status,
        )

        // A coach holds congregation-scoped grants only — no grading desk.
        val (coach, _, _) = api.coachWithTeam("coach@tbb.org", "First Church", listOf(juniorBirthdate))
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/scores") { header(HttpHeaders.Authorization, "Bearer ${coach.token}") }.status,
        )

        // EVENT-scoped GRADER and GLOBAL ADMIN both pass.
        val grader = api.grader(users)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/scores") { header(HttpHeaders.Authorization, "Bearer ${grader.token}") }.status,
        )
        val admin = api.loginSeededAdmin(users)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/scores") { header(HttpHeaders.Authorization, "Bearer ${admin.token}") }.status,
        )
    }

    @Test
    fun sheetRowsCarryCompetingDivisionAndSavesValidate() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        // A junior + an elementary kid on one team (team competes Junior), plus an adult individual.
        api.coachWithTeam(
            "coach@tbb.org", "Mixed Church",
            teamBirthdates = listOf(juniorBirthdate, elementaryBirthdate),
            individualName = "Deacon Dan",
        )
        // A separate all-elementary team: its members do NOT take the Power Round.
        val (_, _, elemReg) = api.coachWithTeam("elem@tbb.org", "Elementary Church", listOf(elementaryBirthdate))
        val elemKid = elemReg.teams.single().members.single()

        val grader = api.grader(users)
        val sheet: GradingSheetResponse = api.get("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()

        assertEquals(openSeason.eventYear, sheet.seasonYear)
        assertNull(sheet.releasedAt)
        assertEquals(4, sheet.rows.size)
        // Both Mixed Church team members compete at the team's division: Junior.
        sheet.rows.filter { it.teamName == "Team A" && it.congregationName == "Mixed Church" }.also { team ->
            assertEquals(2, team.size)
            assertTrue(team.all { it.division == Division.JUNIOR })
        }
        val individual = sheet.rows.single { it.teamName == null }
        assertEquals("Deacon Dan", individual.contestantName)
        assertEquals(Division.ADULT, individual.division)
        assertEquals(Division.ELEMENTARY, sheet.rows.single { it.rosterEntryId == elemKid.id }.division)

        // Valid save round-trips and comes back on the refreshed sheet.
        val junior = sheet.rows.first { it.division == Division.JUNIOR }
        val saved: GradingSheetResponse = api.put("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(
                SaveScoresRequest(
                    listOf(
                        ScoreEntryDto(junior.rosterEntryId, Round.FIND_THE_VERSE, 38),
                        ScoreEntryDto(junior.rosterEntryId, Round.POWER, 45),
                        ScoreEntryDto(elemKid.id, Round.EVENTS, 40),
                    )
                )
            )
        }.body()
        val savedJunior = saved.rows.single { it.rosterEntryId == junior.rosterEntryId }
        assertEquals(mapOf(Round.FIND_THE_VERSE to 38, Round.POWER to 45), savedJunior.scores)

        // Null clears a cell.
        val cleared: GradingSheetResponse = api.put("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(SaveScoresRequest(listOf(ScoreEntryDto(junior.rosterEntryId, Round.POWER, null))))
        }.body()
        assertEquals(
            mapOf(Round.FIND_THE_VERSE to 38),
            cleared.rows.single { it.rosterEntryId == junior.rosterEntryId }.scores,
        )

        // Elementary contestants don't take the Power Round.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.put("/admin/scores") {
                header(HttpHeaders.Authorization, "Bearer ${grader.token}")
                setBody(SaveScoresRequest(listOf(ScoreEntryDto(elemKid.id, Round.POWER, 10))))
            }.status,
        )
        // Points above the round max (Find the Verse caps at 40) are rejected.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.put("/admin/scores") {
                header(HttpHeaders.Authorization, "Bearer ${grader.token}")
                setBody(SaveScoresRequest(listOf(ScoreEntryDto(junior.rosterEntryId, Round.FIND_THE_VERSE, 41))))
            }.status,
        )
        // Unknown roster entries are rejected.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.put("/admin/scores") {
                header(HttpHeaders.Authorization, "Bearer ${grader.token}")
                setBody(SaveScoresRequest(listOf(ScoreEntryDto("no-such-entry", Round.FIND_THE_VERSE, 10))))
            }.status,
        )
        // An invalid batch saves nothing, even when other cells in it are valid.
        api.put("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(
                SaveScoresRequest(
                    listOf(
                        ScoreEntryDto(junior.rosterEntryId, Round.QUOTES, 33),
                        ScoreEntryDto(elemKid.id, Round.POWER, 10),
                    )
                )
            )
        }
        val after: GradingSheetResponse = api.get("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()
        assertNull(after.rows.single { it.rosterEntryId == junior.rosterEntryId }.scores[Round.QUOTES])
    }

    @Test
    fun claimReleaseAndMyScoresScoping() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val (coach, _, reg) = api.coachWithTeam(
            "coach@tbb.org", "Score Church", listOf(juniorBirthdate, juniorBirthdate),
        )
        val (kidEntry, otherEntry) = reg.teams.single().members

        // The contestant claims their entry — dashes/lowercase tolerated, bad codes 404.
        val kid = api.signUp("kid@tbb.org", "Kid", adult = false)
        assertEquals(
            HttpStatusCode.NotFound,
            api.post("/roster/claim") {
                header(HttpHeaders.Authorization, "Bearer ${kid.token}")
                setBody(ClaimEntryRequest("ZZZZ-9999"))
            }.status,
        )
        val dashed = "${kidEntry.claimCode.take(4)}-${kidEntry.claimCode.drop(4).lowercase()}"
        val claimed: RosterEntryDto = api.post("/roster/claim") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
            setBody(ClaimEntryRequest(dashed))
        }.body()
        assertEquals(kidEntry.id, claimed.id)
        assertTrue(claimed.claimed)
        // Someone else can't claim the same entry (but re-claiming your own is idempotent).
        val stranger = api.signUp("stranger@tbb.org", "Stranger", adult = false)
        assertEquals(
            HttpStatusCode.Conflict,
            api.post("/roster/claim") {
                header(HttpHeaders.Authorization, "Bearer ${stranger.token}")
                setBody(ClaimEntryRequest(kidEntry.claimCode))
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            api.post("/roster/claim") {
                header(HttpHeaders.Authorization, "Bearer ${kid.token}")
                setBody(ClaimEntryRequest(kidEntry.claimCode))
            }.status,
        )

        // Grader enters scores; nothing is visible to anyone before release.
        val grader = api.grader(users)
        api.put("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(
                SaveScoresRequest(
                    listOf(
                        ScoreEntryDto(kidEntry.id, Round.FIND_THE_VERSE, 38),
                        ScoreEntryDto(otherEntry.id, Round.FIND_THE_VERSE, 22),
                    )
                )
            )
        }
        val preRelease: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
        }.body()
        assertEquals(false, preRelease.released)
        assertTrue(preRelease.rows.isEmpty())

        // Release requires SCORE_RELEASE (the coach has no such grant); the grader's works.
        assertEquals(
            HttpStatusCode.Forbidden,
            api.put("/admin/scores/release") {
                header(HttpHeaders.Authorization, "Bearer ${coach.token}")
                setBody(SetScoresReleasedRequest(released = true))
            }.status,
        )
        val releasedSheet: GradingSheetResponse = api.put("/admin/scores/release") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(SetScoresReleasedRequest(released = true))
        }.body()
        assertNotNull(releasedSheet.releasedAt)

        // Owner sees exactly their own row; the coach sees the whole congregation.
        val kidView: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
        }.body()
        assertEquals(true, kidView.released)
        assertEquals(listOf(kidEntry.id), kidView.rows.map { it.rosterEntryId })
        assertEquals(mapOf(Round.FIND_THE_VERSE to 38), kidView.rows.single().scores)

        val coachView: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
        }.body()
        assertEquals(setOf(kidEntry.id, otherEntry.id), coachView.rows.map { it.rosterEntryId }.toSet())

        // An unrelated account sees release=true but no rows.
        val strangerView: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${stranger.token}")
        }.body()
        assertTrue(strangerView.rows.isEmpty())

        // Retracting hides everything again.
        api.put("/admin/scores/release") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(SetScoresReleasedRequest(released = false))
        }
        val retracted: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
        }.body()
        assertEquals(false, retracted.released)
        assertTrue(retracted.rows.isEmpty())
    }
}
