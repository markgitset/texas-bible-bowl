package net.markdrew.biblebowl.server

import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import net.markdrew.biblebowl.api.AuthResponse
import net.markdrew.biblebowl.api.ContactInfoDto
import net.markdrew.biblebowl.api.ContactPreference
import net.markdrew.biblebowl.api.EventSiteDto
import net.markdrew.biblebowl.api.ModerateQuestionRequest
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.QuestionDto
import net.markdrew.biblebowl.api.QuestionStatus
import net.markdrew.biblebowl.api.RegisterRequest
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.SeasonDto
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.api.SubmitQuestionRequest
import net.markdrew.biblebowl.api.UpdateProfileRequest
import net.markdrew.biblebowl.api.UserDto
import net.markdrew.biblebowl.model.Round
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reads one entry of an in-memory zip (an .xlsx is a zip of XML parts) as UTF-8 text. */
internal fun readZipEntry(zipBytes: ByteArray, entryName: String): String {
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (entry.name == entryName) return zip.readBytes().decodeToString()
        }
    }
    error("Entry $entryName not found in zip")
}

class ApplicationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun healthReportsSeason() = testApplication {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        application { module(users, questions, JwtService(secret = "test-secret")) }

        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("Acts"))
    }

    @Test
    fun anonymousBrowseSeesApprovedOnly() = testApplication {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val jwt = JwtService(secret = "test-secret")
        application { module(users, questions, jwt) }

        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        // A contestant registers and submits a question (it lands PENDING).
        val contestant: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                setBody(RegisterRequest("kid2@tbb.org", "password123", "Lydia", birthdate = "2013-05-01"))
            }.bodyAsText()
        )
        api.post("/questions") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            setBody(
                SubmitQuestionRequest(
                    roundType = Round.FACT_FINDER,
                    prompt = "Who sold purple goods?",
                    answer = "Lydia",
                    references = listOf("Acts 16:14"),
                    chapter = 16,
                )
            )
        }

        // Anonymous browse works (no token) but never reveals unapproved questions — even when
        // the request explicitly asks for PENDING.
        val anonDefault = api.get("/questions")
        assertEquals(HttpStatusCode.OK, anonDefault.status)
        assertEquals("[]", anonDefault.bodyAsText().trim())

        val anonPending = api.get("/questions?status=PENDING")
        assertEquals(HttpStatusCode.OK, anonPending.status)
        assertEquals("[]", anonPending.bodyAsText().trim(), "anonymous callers are pinned to APPROVED")

        // Signed-in callers can still see the pending queue (their own submissions / moderation).
        val signedInPending = api.get("/questions?status=PENDING") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
        }
        assertTrue(signedInPending.bodyAsText().contains("Who sold purple goods?"))

        // Anonymous writes are still rejected.
        val anonSubmit = api.post("/questions") {
            setBody(
                SubmitQuestionRequest(
                    roundType = Round.FACT_FINDER,
                    prompt = "Anonymous?",
                    answer = "No",
                )
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, anonSubmit.status)
    }

    @Test
    fun questionBankExportsAsQuizletTsvAndKahootXlsx() = testApplication {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val jwt = JwtService(secret = "test-secret")
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        application { module(users, questions, jwt) }

        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        // Seed one approved multiple-choice question.
        val contestant: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                setBody(RegisterRequest("kid3@tbb.org", "password123", "Rhoda", birthdate = "2015-05-01"))
            }.bodyAsText()
        )
        val q: QuestionDto = json.decodeFromString(
            api.post("/questions") {
                header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
                setBody(
                    SubmitQuestionRequest(
                        roundType = Round.FACT_FINDER,
                        prompt = "Who preached at Pentecost?",
                        answer = "Peter",
                        choices = listOf("Peter", "Paul", "John", "James", "Silas"),
                        chapter = 2,
                    )
                )
            }.bodyAsText()
        )
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        api.post("/questions/${q.id}/moderate") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(ModerateQuestionRequest(QuestionStatus.APPROVED))
        }

        // Quizlet TSV: anonymous, one prompt<TAB>answer line.
        val tsv = api.get("/generate/questions.tsv?chapter=2")
        assertEquals(HttpStatusCode.OK, tsv.status)
        assertEquals("Who preached at Pentecost?\tPeter", tsv.bodyAsText().trim())
        assertTrue(tsv.headers[HttpHeaders.ContentDisposition]!!.contains("quizlet-questions-ch2.tsv"))

        // Kahoot xlsx: a zip (PK magic) whose sheet holds the question with at most 4 answers,
        // the correct one preserved.
        val xlsx = api.get("/generate/questions.xlsx?round=FACT_FINDER")
        assertEquals(HttpStatusCode.OK, xlsx.status)
        val bytes = xlsx.bodyAsBytes()
        assertEquals("PK", bytes.decodeToString(0, 2), "xlsx must be a zip")
        val sheet = readZipEntry(bytes, "xl/worksheets/sheet1.xml")
        assertTrue("Who preached at Pentecost?" in sheet)
        assertTrue("Peter" in sheet)
        assertTrue("Silas" !in sheet, "5th choice is dropped (Kahoot allows 4)")

        // No approved matches -> 404.
        assertEquals(HttpStatusCode.NotFound, api.get("/generate/questions.tsv?chapter=27").status)
        // Unknown source -> 400.
        assertEquals(HttpStatusCode.BadRequest, api.get("/generate/questions.tsv?source=nope").status)
    }

    @Test
    fun generateEndpointsAreRateLimitedPerClient() = testApplication {
        application { module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = createClient { }

        // Bad-round requests are cheap (400, no Typst run) but still consume the bucket: 10 pass through…
        repeat(10) {
            assertEquals(HttpStatusCode.BadRequest, api.get("/generate/practice-test.pdf?round=BOGUS").status)
        }
        // …and the 11th within the window is throttled.
        assertEquals(HttpStatusCode.TooManyRequests, api.get("/generate/practice-test.pdf?round=BOGUS").status)
    }

    @Test
    fun seasonIsPubliclyReadableAndAdminEditable() = testApplication {
        val users = InMemoryUserRepository()
        val jwt = JwtService(secret = "test-secret")
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        application { module(users, InMemoryQuestionRepository(), jwt) }

        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        // Anonymous read of the current season (the site's params.js path).
        val res = api.get("/seasons/current")
        assertEquals(HttpStatusCode.OK, res.status)
        val season: SeasonDto = json.decodeFromString(res.bodyAsText())
        assertEquals("Acts", season.eventScripture)
        assertEquals(28, season.chapterCount)

        // Anonymous and non-admin writes are rejected.
        assertEquals(HttpStatusCode.Unauthorized, api.put("/seasons/current") { setBody(season) }.status)
        val kid: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                setBody(RegisterRequest("kid4@tbb.org", "password123", "Priscilla", birthdate = "2012-05-01"))
            }.bodyAsText()
        )
        val forbidden = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${kid.token}")
            setBody(season)
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        // Admin updates; the public read and /health reflect it immediately.
        val admin: AuthResponse = json.decodeFromString(
            api.post("/auth/login") {
                setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
            }.bodyAsText()
        )
        val updated = season.copy(
            eventScripture = "Luke", studySet = "luke", bookCode = "LUK", chapterCount = 24,
            eventTheme = "Certainty", registrationEnabled = true,
        )
        val put = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated)
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val after: SeasonDto = json.decodeFromString(api.get("/seasons/current").bodyAsText())
        assertEquals("Luke", after.eventScripture)
        assertEquals(24, after.chapterCount)
        // The feature toggles ride along on the season; both default off (deploy-dark) until flipped.
        assertEquals(true, after.registrationEnabled)
        assertEquals(false, after.gradingEnabled)
        assertTrue(api.get("/health").bodyAsText().contains("Luke"))

        // Nonsense payloads are rejected.
        val bad = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(chapterCount = 0))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val badSet = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(studySet = "not-a-real-set"))
        }
        assertEquals(HttpStatusCode.BadRequest, badSet.status)

        // Fees are cents (non-negative or null) and registration dates are ISO and ordered.
        val badFee = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(priceContestantCents = -100))
        }
        assertEquals(HttpStatusCode.BadRequest, badFee.status)
        val badDate = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(registrationOpensOn = "in February"))
        }
        assertEquals(HttpStatusCode.BadRequest, badDate.status)
        val backwardsWindow = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(registrationOpensOn = "2027-03-01", registrationClosesOn = "2027-02-01"))
        }
        assertEquals(HttpStatusCode.BadRequest, backwardsWindow.status)
        val priced = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(
                updated.copy(
                    priceContestantCents = 8500,
                    feesTentative = false,
                    registrationOpensOn = "2027-02-01",
                    registrationClosesOn = "2027-03-15",
                )
            )
        }
        assertEquals(HttpStatusCode.OK, priced.status)
        val pricedBack: SeasonDto = json.decodeFromString(api.get("/seasons/current").bodyAsText())
        assertEquals(8500, pricedBack.priceContestantCents)
        assertEquals("2027-03-15", pricedBack.registrationClosesOn)

        // Event sites need non-blank unique ids and names (registrations pin to the id).
        val blankSite = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(sites = listOf(EventSiteDto("s1", " "))))
        }
        assertEquals(HttpStatusCode.BadRequest, blankSite.status)
        val dupeSiteId = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(sites = listOf(EventSiteDto("s1", "Bandina"), EventSiteDto("s1", "White River"))))
        }
        assertEquals(HttpStatusCode.BadRequest, dupeSiteId.status)
        val dupeSiteName = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(sites = listOf(EventSiteDto("s1", "Bandina"), EventSiteDto("s2", "bandina "))))
        }
        assertEquals(HttpStatusCode.BadRequest, dupeSiteName.status)
        val sited = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(updated.copy(sites = listOf(EventSiteDto("s1", "Bandina"), EventSiteDto("s2", "White River Youth Camp"))))
        }
        assertEquals(HttpStatusCode.OK, sited.status)
        val sitedBack: SeasonDto = json.decodeFromString(api.get("/seasons/current").bodyAsText())
        assertEquals(listOf("Bandina", "White River Youth Camp"), sitedBack.sites.map { it.name })

        // New sites arrive from the editors with a blank id and get the slug of their name — the
        // same rule the workbook seed converter applies — deduped against ids already in the list.
        val slugged = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(
                updated.copy(
                    sites = listOf(
                        EventSiteDto("", "Bandina"),
                        EventSiteDto("", "White River Youth Camp"),
                        EventSiteDto("bandina-north", "Bandina North"),
                        EventSiteDto("", "Bandina!"), // distinct name, colliding slug → suffixed
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, slugged.status)
        val sluggedBack: SeasonDto = json.decodeFromString(api.get("/seasons/current").bodyAsText())
        assertEquals(
            listOf("bandina", "white-river-youth-camp", "bandina-north", "bandina-2"),
            sluggedBack.sites.map { it.id },
        )

        // Sites change rarely, so they're inherited season over season: the settings form is
        // prefilled from the current season, and a new-year save (rollover) carries them along
        // with their stable ids.
        val rolled = api.put("/seasons/current") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(sitedBack.copy(eventYear = 2028))
        }
        assertEquals(HttpStatusCode.OK, rolled.status)
        val rolledBack: SeasonDto = json.decodeFromString(api.get("/seasons/current").bodyAsText())
        assertEquals(2028, rolledBack.eventYear)
        assertEquals(sitedBack.sites, rolledBack.sites)
    }

    @Test
    fun legacySeasonPayloadDecodesIntoNewShape() {
        // A pre-migration season row (display-string fees/dates) must decode: unknown keys are
        // ignored and the new numeric fields fall back to their TBD defaults.
        val legacy = """
            {"eventYear":"2027","eventDateRange":"April 2–4","eventTheme":"TBD","eventScripture":"Acts",
             "studySet":"acts","bookCode":"ACT","chapterCount":28,"scholarshipAmount":"$25,000",
             "registrationOpens":"in February","registrationDeadline":"TBD","scholarshipDeadline":"TBD",
             "priceAdult":"TBD (Was $85 in 2026)","priceChild":"TBD (Was $65 in 2026)",
             "priceTshirt":"TBD (Was $10 in 2026)","tbbScholarshipAmount":"$1,000",
             "maryOrbisonAmount":"$1,500","paulHendricksonAmount":"TBD"}
        """.trimIndent()
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<SeasonDto>(legacy)
        assertEquals(2027, decoded.eventYear)
        assertEquals(null, decoded.priceContestantCents)
        assertEquals(null, decoded.registrationOpensOn)
        assertTrue(decoded.feesTentative)
    }

    @Test
    fun registrationRequiresAdultFlagOrBirthdate() = testApplication {
        application { module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        val neither = api.post("/auth/register") {
            setBody(RegisterRequest("x@tbb.org", "password123", "Nameless"))
        }
        assertEquals(HttpStatusCode.BadRequest, neither.status)
        assertTrue(neither.bodyAsText().contains("invalid_birthdate"))

        val badDate = api.post("/auth/register") {
            setBody(RegisterRequest("x@tbb.org", "password123", "Nameless", birthdate = "05/01/2012"))
        }
        assertEquals(HttpStatusCode.BadRequest, badDate.status)

        val adult = api.post("/auth/register") {
            setBody(RegisterRequest("adult@tbb.org", "password123", "Aquila", adult = true))
        }
        assertEquals(HttpStatusCode.Created, adult.status)
        val adultUser: AuthResponse = json.decodeFromString(adult.bodyAsText())
        assertTrue(adultUser.user.adult)
        assertEquals(null, adultUser.user.birthdate)
    }

    @Test
    fun profileEditUpdatesNameAndEligibility() = testApplication {
        application { module(InMemoryUserRepository(), InMemoryQuestionRepository(), JwtService(secret = "test-secret")) }
        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        val auth: AuthResponse = json.decodeFromString(
            api.post("/auth/register") {
                setBody(RegisterRequest("tim@tbb.org", "password123", "Timothy", birthdate = "2013-05-01"))
            }.bodyAsText()
        )

        // Anonymous edits are rejected; bad payloads are rejected.
        assertEquals(
            HttpStatusCode.Unauthorized,
            api.put("/auth/me") { setBody(UpdateProfileRequest("Tim", adult = true)) }.status,
        )
        val blankName = api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("", birthdate = "2013-05-01"))
        }
        assertEquals(HttpStatusCode.BadRequest, blankName.status)
        val noEligibility = api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("Tim"))
        }
        assertEquals(HttpStatusCode.BadRequest, noEligibility.status)

        // A valid edit renames and updates the birthdate; /auth/me reflects it.
        val edited = api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("Tim of Lystra", birthdate = "2012-05-01"))
        }
        assertEquals(HttpStatusCode.OK, edited.status)
        val me: UserDto = json.decodeFromString(
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.bodyAsText()
        )
        assertEquals("Tim of Lystra", me.displayName)
        assertEquals("2012-05-01", me.birthdate)

        // Flipping to adult drops the stored birthdate.
        api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("Tim of Lystra", birthdate = "2012-05-01", adult = true))
        }
        val adultMe: UserDto = json.decodeFromString(
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.bodyAsText()
        )
        assertTrue(adultMe.adult)
        assertEquals(null, adultMe.birthdate)

        // Contact info (item 9, F3): saved when sent, kept when omitted (older clients), cleared when
        // sent empty.
        val contact = ContactInfoDto(
            address = "1 Main St", city = "Waco", state = "TX", zip = "76701",
            phone = "555-0100", preference = ContactPreference.TEXT,
        )
        api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("Tim of Lystra", adult = true, contact = contact))
        }
        val withContact: UserDto = json.decodeFromString(
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.bodyAsText()
        )
        assertEquals(contact, withContact.contact)
        api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("Tim of Lystra", adult = true)) // no contact field at all
        }
        val stillContact: UserDto = json.decodeFromString(
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.bodyAsText()
        )
        assertEquals(contact, stillContact.contact, "omitted contact leaves the stored value alone")
        api.put("/auth/me") {
            header(HttpHeaders.Authorization, "Bearer ${auth.token}")
            setBody(UpdateProfileRequest("Tim of Lystra", adult = true, contact = ContactInfoDto()))
        }
        val cleared: UserDto = json.decodeFromString(
            api.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer ${auth.token}") }.bodyAsText()
        )
        assertEquals(null, cleared.contact, "an all-blank contact clears it")
    }

    @Test
    fun rbacGovernsQuestionModeration() = testApplication {
        val users = InMemoryUserRepository()
        val questions = InMemoryQuestionRepository()
        val jwt = JwtService(secret = "test-secret")
        // Pre-seed a global admin.
        users.create("admin@tbb.org", "Admin", null, adult = true, passwordHash = Passwords.hash("supersecret"), roles = listOf(RoleGrant(Role.ADMIN)))
        application { module(users, questions, jwt) }

        val api = createClient {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }

        // A contestant registers.
        val reg = api.post("/auth/register") {
            setBody(RegisterRequest("kid@tbb.org", "password123", "Timothy", birthdate = "2013-05-01"))
        }
        assertEquals(HttpStatusCode.Created, reg.status)
        val contestant: AuthResponse = json.decodeFromString(reg.bodyAsText())
        assertTrue(Permission.QUESTION_SUBMIT in contestant.user.permissions)
        assertTrue(Permission.QUESTION_MODERATE !in contestant.user.permissions)

        // Text-generated rounds (R1/R4/R5) are NOT crowd-sourced — submitting one is rejected.
        val rejected = api.post("/questions") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            setBody(
                SubmitQuestionRequest(
                    roundType = Round.FIND_THE_VERSE,
                    prompt = "\"Repent and be baptized every one of you\"",
                    answer = "Acts 2:38",
                    references = listOf("Acts 2:38"),
                    chapter = 2,
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)
        assertTrue(rejected.bodyAsText().contains("not_crowd_sourced"))

        // Contestant submits a crowd-sourced (Fact Finder) question.
        val submit = api.post("/questions") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            setBody(
                SubmitQuestionRequest(
                    roundType = Round.FACT_FINDER,
                    prompt = "Who preached at Pentecost?",
                    answer = "Peter",
                    references = listOf("Acts 2:14"),
                    choices = listOf("Peter", "Paul", "John", "James"),
                    chapter = 2,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, submit.status)
        val q: QuestionDto = json.decodeFromString(submit.bodyAsText())
        assertEquals(QuestionStatus.PENDING, q.status)

        // Contestant CANNOT moderate -> 403.
        val forbidden = api.post("/questions/${q.id}/moderate") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
            setBody(ModerateQuestionRequest(QuestionStatus.APPROVED))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        // Admin logs in and approves -> 200.
        val adminLogin = api.post("/auth/login") {
            setBody(net.markdrew.biblebowl.api.LoginRequest("admin@tbb.org", "supersecret"))
        }
        assertEquals(HttpStatusCode.OK, adminLogin.status)
        val admin: AuthResponse = json.decodeFromString(adminLogin.bodyAsText())

        val approve = api.post("/questions/${q.id}/moderate") {
            header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            setBody(ModerateQuestionRequest(QuestionStatus.APPROVED))
        }
        assertEquals(HttpStatusCode.OK, approve.status)
        val approved: QuestionDto = json.decodeFromString(approve.bodyAsText())
        assertEquals(QuestionStatus.APPROVED, approved.status)

        // The approved question now shows up in the default (approved) listing.
        val list = api.get("/questions") {
            header(HttpHeaders.Authorization, "Bearer ${contestant.token}")
        }
        assertEquals(HttpStatusCode.OK, list.status)
        assertTrue(list.bodyAsText().contains("Who preached at Pentecost?"))
    }
}
