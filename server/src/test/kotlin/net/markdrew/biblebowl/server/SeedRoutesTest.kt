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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.EnrollContestantRequest
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.LoginRequest
import net.markdrew.biblebowl.api.MyRegistrationResponse
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.ScopeType
import net.markdrew.biblebowl.api.SeedCongregationDto
import net.markdrew.biblebowl.api.SeedGuestDto
import net.markdrew.biblebowl.api.SeedIndividualDto
import net.markdrew.biblebowl.api.SeedMemberDto
import net.markdrew.biblebowl.api.SeedRequest
import net.markdrew.biblebowl.api.SeedSummary
import net.markdrew.biblebowl.api.SeedTeamDto
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.TesterListResponse
import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import net.markdrew.biblebowl.server.data.InMemoryCongregationRepository
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryRegistrationRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeedRoutesTest {

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

    /** A miniature 2026 workbook: two congregations, one combo team, an individual, and a guest. */
    private fun sampleSeed() = SeedRequest(
        seasonYear = "2026",
        congregations = listOf(
            SeedCongregationDto(
                name = "First Church", city = "Austin", state = "TX",
                mailingAddress = "1 Main St", zip = "78701", phone = "512-555-0100", code = "FC",
                siteId = "bandina",
                coachEmails = listOf("coach@first.org"),
                teams = listOf(
                    SeedTeamDto(
                        "Torch Bearers",
                        members = listOf(
                            SeedMemberDto("Sam Senior", Gender.MALE, ShirtSize.AM, grade = 10),
                            // A combo-team visiting member from the other congregation.
                            SeedMemberDto(
                                "Vera Visitor", Gender.FEMALE, ShirtSize.YL, grade = 11,
                                congregationName = "Second Church",
                            ),
                        ),
                    )
                ),
                unassigned = listOf(
                    SeedMemberDto("Ellie Elementary", Gender.FEMALE, ShirtSize.YM, grade = 4, inexperienced = true),
                ),
                individuals = listOf(
                    SeedIndividualDto("Adam Adult", Gender.MALE, ShirtSize.AL, tribeLeaderWilling = true),
                ),
                guests = listOf(
                    SeedGuestDto(
                        "Greta Guest", Gender.FEMALE, ShirtSize.AM,
                        positions = listOf("Kitchen Helper"), tribeLeaderWilling = true,
                        contact = ContactInfoDto(phone = "512-555-0111", preference = ContactPreference.PHONE),
                    ),
                ),
            ),
            SeedCongregationDto(name = "Second Church", city = "Waco", code = "SC", siteId = "bandina"),
        ),
    )

    @Test
    fun seedIsAdminOnly() = testApplication {
        val users = InMemoryUserRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason))
        }
        val api = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, api.post("/admin/seed") { setBody(sampleSeed()) }.status)

        // Even an event-wide REGISTRAR is refused — the seed is for global admins only.
        val registrar: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("registrar@tbb.org", "password123", "Reg Istrar", adult = true))
        }.body()
        users.addRoleGrant(registrar.user.id, RoleGrant(Role.REGISTRAR))
        assertEquals(
            HttpStatusCode.Forbidden,
            api.post("/admin/seed") {
                header(HttpHeaders.Authorization, "Bearer ${registrar.token}")
                setBody(sampleSeed())
            }.status,
        )
    }

    @Test
    fun seedIsIdempotentAndCarriesTheWholeWorkbookShape() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations: RegistrationRepository = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason),
                congregations = congregations, registrations = registrations)
        }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)

        val first: SeedSummary = api.post("/admin/seed") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(sampleSeed())
        }.body()
        assertEquals(2, first.congregations)
        assertEquals(1, first.teams)
        assertEquals(3, first.members)
        assertEquals(1, first.individuals)
        assertEquals(1, first.guests)
        assertEquals(1, first.pendingCoachGrants)
        assertEquals(emptyList(), first.warnings)

        // Re-running changes nothing: same totals, no duplicated rows.
        val second: SeedSummary = api.post("/admin/seed") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(sampleSeed())
        }.body()
        assertEquals(first.copy(warnings = second.warnings), second)

        val cong = congregations.listAll().single { it.name == "First Church" }
        assertEquals("FC", cong.code)
        assertEquals("512-555-0100", cong.phone)
        val reg = registrations.find(cong.id, "2026")
        assertNotNull(reg)
        assertEquals("bandina", reg.siteId)
        assertNotNull(reg.submittedAt)
        assertNotNull(reg.paidAt)
        val team = reg.teams.single()
        // The visiting member displays with her own congregation (combo rule).
        val visitor = team.members.single { it.name == "Vera Visitor" }
        assertEquals("Second Church", visitor.participation.congregationName)
        assertEquals(listOf("Ellie Elementary"), reg.unassigned.map { it.name })
        // Inexperienced in 2026 anchors the first season; the guest carries positions + contact.
        assertEquals("2026", reg.unassigned.single().firstSeasonYear)
        val guest = reg.guests.single()
        assertEquals(listOf("Kitchen Helper"), guest.positions)
        assertEquals(ContactPreference.PHONE, guest.contact?.preference)

        // The seeded youth are returning candidates for 2027 with grade-derived graduation years —
        // except Sam (grade 10 in 2026): still eligible (grade 11 in 2027). Ellie: grade 5.
        val candidates = registrations.returningContestants(cong.id, "2027")
        val ellie = candidates.single { it.name == "Ellie Elementary" }
        assertNull(ellie.birthdate)
        assertEquals(2026 + (12 - 4), ellie.graduationYear)
    }

    @Test
    fun seedResolvesSiteSlugsSoMultiSiteTesterNumberingWorks() = testApplication {
        // A multi-site current season whose sites carry pre-slug-convention random ids — exactly
        // the setup where an unresolved seeded siteId would leave every tester un-numbered.
        val bandina = EventSiteDto("site-x1y2z3w4", "Bandina")
        val whiteRiver = EventSiteDto("site-a5b6c7d8", "White River Youth Camp")
        val season = openSeason.copy(sites = listOf(bandina, whiteRiver))
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations: RegistrationRepository = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(season),
                congregations = congregations, registrations = registrations)
        }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)

        // The first two siteIds are the converter's name-slugs; the third matches nothing.
        val seed = SeedRequest(
            seasonYear = season.eventYear.toString(),
            congregations = listOf(
                SeedCongregationDto(
                    name = "First Church", city = "Austin", code = "FC", siteId = "bandina",
                    teams = listOf(
                        SeedTeamDto(
                            "Torch Bearers",
                            members = listOf(SeedMemberDto("Sam Senior", Gender.MALE, ShirtSize.AM, grade = 10)),
                        )
                    ),
                ),
                SeedCongregationDto(
                    name = "Second Church", city = "Waco", code = "SC", siteId = "white-river-youth-camp",
                    unassigned = listOf(SeedMemberDto("Ursula Unassigned", Gender.FEMALE, ShirtSize.YL, grade = 8)),
                ),
                SeedCongregationDto(name = "Third Church", city = "Tyler", code = "TC", siteId = "gone-fishing"),
            ),
        )
        val summary: SeedSummary = api.post("/admin/seed") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(seed)
        }.body()

        // Slugged siteIds resolved to the season's real site ids; the unknown one is kept + warned.
        fun regFor(name: String) =
            registrations.find(congregations.listAll().single { it.name == name }.id, season.eventYear.toString())
        assertEquals(bandina.id, regFor("First Church")?.siteId)
        assertEquals(whiteRiver.id, regFor("Second Church")?.siteId)
        assertEquals("gone-fishing", regFor("Third Church")?.siteId)
        assertTrue(summary.warnings.single().startsWith("Third Church: site \"gone-fishing\""))

        // End-to-end: seeded testers number in the season-wide sequence, site-grouped in season
        // order on first assignment (Bandina before White River).
        val testers: TesterListResponse = api.get("/admin/testers") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
        }.body()
        assertEquals(1, testers.rows.single { it.name == "Sam Senior" }.testerId)
        assertEquals(2, testers.rows.single { it.name == "Ursula Unassigned" }.testerId)
    }

    @Test
    fun coachEmailSignupGetsTheScopedCoachRole() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason), congregations = congregations)
        }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)
        api.post("/admin/seed") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(sampleSeed())
        }

        // Signing up with the workbook's coach email consumes the pending grant.
        val coach: AuthResponse = api.post("/auth/register") {
            setBody(RegisterRequest("Coach@First.org", "password123", "First Coach", adult = true))
        }.body()
        val congId = congregations.listAll().single { it.name == "First Church" }.id
        assertTrue(coach.user.roles.any {
            it.role == Role.COACH && it.scopeType == ScopeType.CONGREGATION && it.scopeId == congId
        })
        assertTrue(users.pendingCoachGrants().isEmpty())

        // And the coach's register screen resumes straight into the congregation.
        val mine: MyRegistrationResponse = api.get("/registration/mine") {
            header(HttpHeaders.Authorization, "Bearer ${coach.token}")
        }.body()
        assertEquals(listOf("First Church"), mine.congregations.map { it.name })
    }

    @Test
    fun seededYouthEnrollRequiresABirthdateOnce() = testApplication {
        val users = InMemoryUserRepository()
        val congregations = InMemoryCongregationRepository()
        val registrations: RegistrationRepository = InMemoryRegistrationRepository(congregations)
        application {
            module(users, InMemoryQuestionRepository(), JwtService(secret = "test-secret"),
                seasons = InMemorySeasonRepository(openSeason),
                congregations = congregations, registrations = registrations)
        }
        val api = jsonClient()
        val admin = api.loginSeededAdmin(users)
        api.post("/admin/seed") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(sampleSeed())
        }
        val congId = congregations.listAll().single { it.name == "First Church" }.id
        val ellie = registrations.returningContestants(congId, "2027").single { it.name == "Ellie Elementary" }

        fun io.ktor.client.request.HttpRequestBuilder.asAdmin() =
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")

        // Without a birthdate the enrollment is refused with a pointed error…
        val refused = api.post("/registration/$congId/contestants/${ellie.contestantId}/enroll") {
            asAdmin(); setBody(EnrollContestantRequest(ShirtSize.YM))
        }
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals("birthdate_required", refused.body<ApiError>().code)

        // …an out-of-range birthdate is rejected like any roster entry's…
        val tooOld = api.post("/registration/$congId/contestants/${ellie.contestantId}/enroll") {
            asAdmin(); setBody(EnrollContestantRequest(ShirtSize.YM, birthdate = "1990-01-01"))
        }
        assertEquals(HttpStatusCode.BadRequest, tooOld.status)

        // …and a valid one enrolls them AND sticks on the durable contestant (grade 5 in 2027).
        val ok = api.post("/registration/$congId/contestants/${ellie.contestantId}/enroll") {
            asAdmin(); setBody(EnrollContestantRequest(ShirtSize.YM, birthdate = "2015-05-01"))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val entry = registrations.find(congId, "2027")!!.unassigned.single { it.name == "Ellie Elementary" }
        assertEquals("2015-05-01", entry.birthdate)
        // No longer a candidate (enrolled), and future seasons see a normal birthdate youth.
        assertTrue(registrations.returningContestants(congId, "2027").none { it.name == "Ellie Elementary" })
    }
}
