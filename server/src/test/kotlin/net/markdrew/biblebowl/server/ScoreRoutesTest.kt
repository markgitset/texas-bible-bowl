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
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AssignMemberTeamRequest
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
import net.markdrew.biblebowl.api.StandingsResponse
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
        registrationEnabled = true,
        gradingEnabled = true,
    )

    // Season 2027 → grade cutoff 2026-09-01: born 2011 = grade 10 (Senior), born 2013 = grade 8
    // (Junior), born 2017 = grade 4 (Elementary).
    private val seniorBirthdate = "2011-05-01"
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
        val (_, _, mixedReg) = api.coachWithTeam(
            "coach@tbb.org", "Mixed Church",
            teamBirthdates = listOf(juniorBirthdate, elementaryBirthdate),
            individualName = "Deacon Dan",
        )
        val mixedElemKid = mixedReg.teams.single().members.single { it.birthdate == elementaryBirthdate }
        // An elementary congregation: one kid stays on the team — there are no Elementary teams,
        // so an all-elementary roster plays up as Junior — and one is unassigned, competing
        // individually as Elementary (the normal case). Neither takes the Power Round.
        val (elemCoach, _, elemReg) = api.coachWithTeam(
            "elem@tbb.org", "Elementary Church", listOf(elementaryBirthdate, elementaryBirthdate),
        )
        val (elemKid, soloKid) = elemReg.teams.single().members
        api.put("/registration/members/${soloKid.id}/team") {
            header(HttpHeaders.Authorization, "Bearer ${elemCoach.token}")
            setBody(AssignMemberTeamRequest(null))
        }

        val grader = api.grader(users)
        val sheet: GradingSheetResponse = api.get("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()

        assertEquals(openSeason.eventYear, sheet.seasonYear)
        assertNull(sheet.releasedAt)
        assertEquals(5, sheet.rows.size)
        // Each Mixed Church member keeps their OWN division; the team's (Junior) rides alongside.
        sheet.rows.filter { it.teamName == "Team A" && it.congregationName == "Mixed Church" }.also { team ->
            assertEquals(2, team.size)
            assertEquals(setOf(Division.JUNIOR, Division.ELEMENTARY), team.map { it.division }.toSet())
            assertTrue(team.all { it.teamDivision == Division.JUNIOR })
        }
        val individual = sheet.rows.single { it.contestantName == "Deacon Dan" }
        assertNull(individual.teamName)
        assertEquals(Division.ADULT, individual.division)
        assertNull(individual.teamDivision)
        // The all-elementary team plays up as Junior; its member is still Elementary individually.
        sheet.rows.single { it.rosterEntryId == elemKid.id }.also { playUp ->
            assertEquals(Division.ELEMENTARY, playUp.division)
            assertEquals(Division.JUNIOR, playUp.teamDivision)
        }
        // The unassigned kid competes individually in her own division — no team bracket at all.
        sheet.rows.single { it.rosterEntryId == soloKid.id }.also { solo ->
            assertNull(solo.teamName)
            assertEquals(Division.ELEMENTARY, solo.division)
            assertNull(solo.teamDivision)
        }

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
                        ScoreEntryDto(soloKid.id, Round.EVENTS, 35), // unassigned entries are gradeable
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

        // Elementary contestants don't take the Power Round — even on a team competing Junior:
        // rounds follow the contestant's OWN division, not the team's elevated one.
        assertEquals(
            HttpStatusCode.BadRequest,
            api.put("/admin/scores") {
                header(HttpHeaders.Authorization, "Bearer ${grader.token}")
                setBody(SaveScoresRequest(listOf(ScoreEntryDto(elemKid.id, Round.POWER, 10))))
            }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            api.put("/admin/scores") {
                header(HttpHeaders.Authorization, "Bearer ${grader.token}")
                setBody(SaveScoresRequest(listOf(ScoreEntryDto(mixedElemKid.id, Round.POWER, 10))))
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
    fun standingsRankBracketsWithTeamTotalsExcludingPower() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        // Two junior teams in different congregations, plus an adult individual.
        val (coachA, _, regA) = api.coachWithTeam(
            "a@tbb.org", "Alpha Church", listOf(juniorBirthdate, juniorBirthdate),
        )
        val (memberA1, memberA2) = regA.teams.single().members
        val (_, _, regB) = api.coachWithTeam(
            "b@tbb.org", "Beta Church", listOf(juniorBirthdate), individualName = "Deacon Dan",
        )
        val memberB1 = regB.teams.single().members.single()
        // Plus an unassigned elementary contestant, who competes individually in her own bracket.
        val (coachG, _, regG) = api.coachWithTeam("g@tbb.org", "Gamma Church", listOf(elementaryBirthdate))
        val soloElem = regG.teams.single().members.single()
        api.put("/registration/members/${soloElem.id}/team") {
            header(HttpHeaders.Authorization, "Bearer ${coachG.token}")
            setBody(AssignMemberTeamRequest(null))
        }

        // The tally is a cross-congregation surface: coaches get 403, graders pass.
        assertEquals(
            HttpStatusCode.Forbidden,
            api.get("/admin/scores/standings") {
                header(HttpHeaders.Authorization, "Bearer ${coachA.token}")
            }.status,
        )
        val grader = api.grader(users)

        // A1: 40 + Power 50 → individual 90, but only 40 counts for the team.
        // A2: 30 → team Alpha = 70.  B1: 35 + 35 = 70 → team Beta = 70 (a team tie).
        // Dan (adult individual): 40.
        api.put("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(
                SaveScoresRequest(
                    listOf(
                        ScoreEntryDto(memberA1.id, Round.FIND_THE_VERSE, 40),
                        ScoreEntryDto(memberA1.id, Round.POWER, 50),
                        ScoreEntryDto(memberA2.id, Round.QUOTES, 30),
                        ScoreEntryDto(memberB1.id, Round.FIND_THE_VERSE, 35),
                        ScoreEntryDto(memberB1.id, Round.EVENTS, 35),
                        ScoreEntryDto(regB.individuals.single().id, Round.FIND_THE_VERSE, 40),
                        ScoreEntryDto(soloElem.id, Round.QUOTES, 25),
                    )
                )
            )
        }

        val standings: StandingsResponse = api.get("/admin/scores/standings") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()
        assertEquals(openSeason.eventYear, standings.seasonYear)
        assertNull(standings.releasedAt)
        assertEquals(
            listOf(Division.ELEMENTARY, Division.JUNIOR, Division.ADULT),
            standings.divisions.map { it.division },
        )

        // The unassigned elementary contestant ranks in the Elementary bracket (max 200, no teams).
        val elementary = standings.divisions.first()
        assertEquals(listOf(soloElem.id), elementary.individuals.map { it.rosterEntryId })
        assertEquals(listOf(25), elementary.individuals.map { it.points })
        assertTrue(elementary.individuals.all { it.maxPoints == 200 })
        assertTrue(elementary.teams.isEmpty(), "there are no Elementary teams")

        val junior = standings.divisions[1]
        // Individuals rank by their full totals (Power included): 90, 70, 30.
        assertEquals(listOf(1, 2, 3), junior.individuals.map { it.rank })
        assertEquals(listOf(90, 70, 30), junior.individuals.map { it.points })
        assertEquals(listOf(memberA1.id, memberB1.id, memberA2.id), junior.individuals.map { it.rosterEntryId })
        assertTrue(junior.individuals.all { it.maxPoints == 250 })
        // Teams tie at 70 (Alpha's Power 50 doesn't count) and share rank 1.
        assertEquals(listOf(1, 1), junior.teams.map { it.rank })
        assertEquals(listOf(70, 70), junior.teams.map { it.points })
        assertEquals(setOf("Alpha Church", "Beta Church"), junior.teams.map { it.congregationName }.toSet())
        assertEquals(400, junior.teams.first { it.congregationName == "Alpha Church" }.maxPoints)
        assertEquals(200, junior.teams.first { it.congregationName == "Beta Church" }.maxPoints)

        val adult = standings.divisions.last()
        assertEquals(listOf(1), adult.individuals.map { it.rank })
        assertEquals(listOf(40), adult.individuals.map { it.points })
        assertTrue(adult.teams.isEmpty(), "adults never form teams")

        // Post-release, My Scores rows carry placement ranked against the whole field.
        api.put("/admin/scores/release") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(SetScoresReleasedRequest(released = true))
        }
        val coachView: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${coachA.token}")
        }.body()
        val a1 = coachView.rows.single { it.rosterEntryId == memberA1.id }
        assertEquals(1, a1.rank)
        assertEquals(3, a1.rankOf)
        assertEquals(1, a1.teamRank)
        assertEquals(2, a1.teamRankOf)
        assertEquals(70, a1.teamPoints)
        val a2 = coachView.rows.single { it.rosterEntryId == memberA2.id }
        assertEquals(3, a2.rank)
        assertEquals(70, a2.teamPoints)
    }

    @Test
    fun individualsRankInTheirOwnBracketWhileTheTeamCompetesElevated() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        // The 2026-workbook case: a senior + a junior (both experienced) and a first-year junior
        // share one team. The team competes Senior-Experienced; each member's individual placement
        // stays in their own bracket.
        val (coach, _, reg) = api.coachWithTeam(
            "coach@tbb.org", "Split Church", listOf(seniorBirthdate, juniorBirthdate),
        )
        var updated: RegistrationDto = api.post("/registration/teams/${reg.teams.single().id}/members") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
            setBody(
                UpsertRosterEntryRequest(
                    "First-Year Fran", birthdate = juniorBirthdate,
                    shirtSize = ShirtSize.YL, gender = Gender.FEMALE, inexperienced = true,
                )
            )
        }.body()
        val members = updated.teams.single().members
        val senior = members.single { it.birthdate == seniorBirthdate }
        val juniorExp = members.single { it.birthdate == juniorBirthdate && it.firstSeasonYear == null }
        val juniorNew = members.single { it.name == "First-Year Fran" }

        val grader = api.grader(users)
        // Individual totals: senior 20 + Power 50 = 70, junior 40, first-year 30.
        // Team total (rounds 1–5 only): 20 + 40 + 30 = 90.
        api.put("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(
                SaveScoresRequest(
                    listOf(
                        ScoreEntryDto(senior.id, Round.FIND_THE_VERSE, 20),
                        ScoreEntryDto(senior.id, Round.POWER, 50),
                        ScoreEntryDto(juniorExp.id, Round.FIND_THE_VERSE, 40),
                        ScoreEntryDto(juniorNew.id, Round.FIND_THE_VERSE, 30),
                    )
                )
            )
        }

        val standings: StandingsResponse = api.get("/admin/scores/standings") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()
        // Three brackets: each member ranks individually in their own; the team only in Senior.
        assertEquals(
            listOf(
                Division.JUNIOR to false,
                Division.JUNIOR to true,
                Division.SENIOR to false,
            ),
            standings.divisions.map { it.division to it.inexperienced },
        )
        val (juniorExpBracket, juniorNewBracket, seniorBracket) = standings.divisions
        assertEquals(listOf(juniorExp.id), juniorExpBracket.individuals.map { it.rosterEntryId })
        assertEquals(listOf(1), juniorExpBracket.individuals.map { it.rank })
        assertTrue(juniorExpBracket.teams.isEmpty())
        assertEquals(listOf(juniorNew.id), juniorNewBracket.individuals.map { it.rosterEntryId })
        assertTrue(juniorNewBracket.teams.isEmpty())
        assertEquals(listOf(senior.id), seniorBracket.individuals.map { it.rosterEntryId })
        assertEquals(listOf(70), seniorBracket.individuals.map { it.points })
        assertEquals(listOf(90), seniorBracket.teams.map { it.points })
        assertEquals(listOf(600), seniorBracket.teams.map { it.maxPoints })

        // My Scores: the junior's individual placement is in her own bracket (1 of 1), while her
        // team placement reflects the elevated Senior bracket.
        api.put("/admin/scores/release") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
            setBody(SetScoresReleasedRequest(released = true))
        }
        val coachView: MyScoresResponse = api.get("/scores/mine") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
        }.body()
        val juniorRow = coachView.rows.single { it.rosterEntryId == juniorExp.id }
        assertEquals(Division.JUNIOR, juniorRow.division)
        assertEquals(false, juniorRow.inexperienced)
        assertEquals(Division.SENIOR, juniorRow.teamDivision)
        assertEquals(1, juniorRow.rank)
        assertEquals(1, juniorRow.rankOf)
        assertEquals(1, juniorRow.teamRank)
        assertEquals(1, juniorRow.teamRankOf)
        assertEquals(90, juniorRow.teamPoints)
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

    @Test
    fun scoringIsDarkUntilTheFeatureToggleIsOn() = testApplication {
        val users = InMemoryUserRepository()
        application {
            // Registration launched, scoring not yet (the deploy-dark default).
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason.copy(gradingEnabled = false)))
        }
        val api = jsonClient()

        // Even a GRADER's grading sheet and a contestant's My Scores answer 403 feature_disabled.
        val grader = api.grader(users)
        val sheet = api.get("/admin/scores") { header(HttpHeaders.Authorization, "Bearer ${grader.token}") }
        assertEquals(HttpStatusCode.Forbidden, sheet.status)
        assertEquals("feature_disabled", sheet.body<ApiError>().code)
        val kid = api.signUp("kid@tbb.org", "Priscilla", adult = false)
        val mine = api.get("/scores/mine") { header(HttpHeaders.Authorization, "Bearer ${kid.token}") }
        assertEquals(HttpStatusCode.Forbidden, mine.status)
        assertEquals("feature_disabled", mine.body<ApiError>().code)

        // Global admins bypass the toggle so the dark-deployed feature can be tested in prod.
        val admin = api.loginSeededAdmin(users)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/admin/scores") { header(HttpHeaders.Authorization, "Bearer ${admin.token}") }.status,
        )
    }

    @Test
    fun comboTeamMembersScoreUnderTheirOwnCongregation() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()

        val (_, _, regA) = api.coachWithTeam("alice@tbb.org", "Alpha Church", listOf(juniorBirthdate))
        val (_, _, regB) = api.coachWithTeam("bob@tbb.org", "Beta Church", listOf(juniorBirthdate))

        // A registrar pools the two congregations: Beta's kid joins Alpha's team (a combo team).
        val registrar = api.signUp("registrar@tbb.org", "Reggie Registrar")
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        val placed = api.put("/registration/members/${regB.teams.single().members.single().id}/team") {
            header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
            setBody(AssignMemberTeamRequest(regA.teams.single().id))
        }
        assertEquals(HttpStatusCode.OK, placed.status)

        // The grading sheet shows the visiting member under their OWN congregation, host team named.
        val grader = api.grader(users)
        val sheet: GradingSheetResponse = api.get("/admin/scores") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()
        val visiting = sheet.rows.single { it.contestantName == "Kid 0 of Beta Church" }
        assertEquals("Beta Church", visiting.congregationName)
        assertEquals("Team A", visiting.teamName)
        assertEquals(Division.JUNIOR, visiting.teamDivision)

        // The combo team's standings row names both congregations.
        val standings: StandingsResponse = api.get("/admin/scores/standings") {
            header(HttpHeaders.Authorization, "Bearer ${grader.token}")
        }.body()
        val junior = standings.divisions.single { it.division == Division.JUNIOR && !it.inexperienced }
        assertEquals("Alpha Church + Beta Church", junior.teams.single().congregationName)
    }
}
