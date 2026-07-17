package net.markdrew.biblebowl.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Role
import net.markdrew.biblebowl.api.RoleGrant
import net.markdrew.biblebowl.server.data.CongregationRepository
import net.markdrew.biblebowl.server.data.DatabaseFactory
import net.markdrew.biblebowl.server.data.InMemoryCongregationRepository
import net.markdrew.biblebowl.server.data.InMemoryQuestionRepository
import net.markdrew.biblebowl.server.data.InMemoryRegistrationRepository
import net.markdrew.biblebowl.server.data.InMemorySeasonRepository
import net.markdrew.biblebowl.server.data.InMemoryUserRepository
import net.markdrew.biblebowl.server.data.PostgresCongregationRepository
import net.markdrew.biblebowl.server.data.PostgresQuestionRepository
import net.markdrew.biblebowl.server.data.PostgresRegistrationRepository
import net.markdrew.biblebowl.server.data.PostgresSeasonRepository
import net.markdrew.biblebowl.server.data.PostgresUserRepository
import net.markdrew.biblebowl.server.data.QuestionRepository
import net.markdrew.biblebowl.server.data.RegistrationRepository
import net.markdrew.biblebowl.server.data.SeasonRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.esv.EsvPassageService
import net.markdrew.biblebowl.server.esv.FileEsvCache
import net.markdrew.biblebowl.server.esv.PostgresEsvCache
import net.markdrew.biblebowl.server.routes.GENERATE_RATE_LIMIT
import net.markdrew.biblebowl.server.routes.authRoutes
import net.markdrew.biblebowl.server.routes.bibleRoutes
import net.markdrew.biblebowl.server.routes.generateRoutes
import net.markdrew.biblebowl.server.routes.questionRoutes
import net.markdrew.biblebowl.server.routes.registrationRoutes
import net.markdrew.biblebowl.server.routes.seasonRoutes
import net.markdrew.biblebowl.server.routes.studyRoutes
import net.markdrew.biblebowl.server.security.JwtService
import net.markdrew.biblebowl.server.security.Passwords
import net.markdrew.biblebowl.server.study.InMemoryPdfCache
import net.markdrew.biblebowl.server.study.PdfCache
import net.markdrew.biblebowl.server.study.PostgresAnnotationCache
import net.markdrew.biblebowl.server.study.PostgresPdfCache
import net.markdrew.biblebowl.server.study.StudyDataService

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Use Postgres when configured (production / docker-compose dev); fall back to in-memory otherwise.
    val db = if (System.getenv("DATABASE_URL") != null) DatabaseFactory.connect() else null
    val users = db?.let(::PostgresUserRepository) ?: InMemoryUserRepository()
    val questions = db?.let(::PostgresQuestionRepository) ?: InMemoryQuestionRepository()
    val seasons = db?.let(::PostgresSeasonRepository) ?: InMemorySeasonRepository()
    val congregations = db?.let(::PostgresCongregationRepository) ?: InMemoryCongregationRepository()
    val registrations = db?.let(::PostgresRegistrationRepository) ?: InMemoryRegistrationRepository(congregations)
    // Prod uses the Postgres cache; local dev (no DATABASE_URL) uses a persisted on-disk cache so repeated
    // runs never re-hit the ESV API — only a first run (cache miss) or ESV_CACHE_REFRESH re-fetches. It
    // lives under the user's home (~/.cache/texas-bible-bowl/esv) so it survives git cleans and fresh clones.
    val esvCacheDir = System.getenv("ESV_CACHE_DIR")
        ?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".cache", "texas-bible-bowl", "esv")
    val esvCache = db?.let(::PostgresEsvCache) ?: FileEsvCache(
        dir = esvCacheDir,
        refresh = System.getenv("ESV_CACHE_REFRESH")?.toBooleanStrictOrNull() == true,
    )
    val esv = EsvPassageService(client = HttpClient(CIO), cache = esvCache)
    // Persist the text-analysis (highlight category) resolution in Postgres when available, so it survives
    // scale-to-zero restarts instead of being recomputed on each cold start.
    val study = StudyDataService(esv, annotationCache = db?.let(::PostgresAnnotationCache))
    // Compiled-PDF cache: Postgres in prod (survives scale-to-zero), bounded in-memory in local dev.
    val pdfCache = db?.let(::PostgresPdfCache) ?: InMemoryPdfCache()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(
            users, questions, esv = esv, study = study, seasons = seasons, pdfCache = pdfCache,
            congregations = congregations, registrations = registrations,
        )
    }.start(wait = true)
}

/**
 * The application module. Repositories and [jwt] are injectable so tests can supply in-memory doubles or
 * pre-seeded data. In production these default to the in-memory implementations until Phase 1 wires Postgres.
 */
fun Application.module(
    users: UserRepository = InMemoryUserRepository(),
    questions: QuestionRepository = InMemoryQuestionRepository(),
    jwt: JwtService = JwtService(),
    esv: EsvPassageService? = null,
    study: StudyDataService? = esv?.let(::StudyDataService),
    seasons: SeasonRepository = InMemorySeasonRepository(),
    pdfCache: PdfCache? = null,
    congregations: CongregationRepository = InMemoryCongregationRepository(),
    registrations: RegistrationRepository = InMemoryRegistrationRepository(congregations),
) {
    seedAdminFromEnv(users)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging)
    install(CORS) {
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        HttpMethod.DefaultMethods.forEach { allowMethod(it) }
        // Production: restrict to the web app's origin(s) via ALLOWED_ORIGINS (comma-separated,
        // e.g. "https://markgitset.github.io"). Unset (dev/tests) stays permissive.
        val origins = System.getenv("ALLOWED_ORIGINS")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
        if (origins.isEmpty()) {
            anyHost()
        } else {
            origins.forEach { origin ->
                val scheme = origin.substringBefore("://", "https")
                val host = origin.substringAfter("://")
                allowHost(host, schemes = listOf(scheme))
            }
        }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", cause.message ?: "Unexpected error"))
        }
    }
    install(Authentication) {
        jwt {
            realm = "texas-bible-bowl"
            verifier(jwt.verifier)
            validate { cred -> cred.subject?.let { JWTPrincipal(cred.payload) } }
        }
    }
    install(RateLimit) {
        // Mild per-client limit on the public /generate/* endpoints (each request runs Typst, which is
        // CPU-bound). Keyed on the original client IP: Fly's proxy terminates the connection, so the
        // socket address alone would lump all users into one bucket.
        register(GENERATE_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 60.seconds)
            requestKey { call ->
                call.request.headers["Fly-Client-IP"]
                    ?: call.request.headers[HttpHeaders.XForwardedFor]?.substringBefore(',')?.trim()
                    ?: call.request.origin.remoteHost
            }
        }
    }

    routing {
        get("/health") {
            call.respond(
                mapOf("status" to "ok", "service" to "texas-bible-bowl", "season" to seasons.current().eventScripture),
            )
        }
        authRoutes(users, jwt)
        questionRoutes(users, questions)
        bibleRoutes(esv)
        studyRoutes(study)
        generateRoutes(users, questions, seasons, study, pdfCache)
        seasonRoutes(users, seasons)
        registrationRoutes(users, seasons, congregations, registrations)
    }

    warmStudyCache(study)
}

/**
 * When PRIME_CACHE_ON_START is set, indexes the season's [StudyData] in the background so the ESV chapter
 * cache is warm before the first study/headings or text-generated practice-test request. Cache-first, so
 * after the first successful run every restart is served from Postgres with no live ESV calls. Non-blocking
 * (won't delay health checks) and best-effort — a failure just falls back to lazy on-demand loading.
 *
 * Gated by an env flag (not on by default) so tests never trigger live ESV fetches.
 */
private fun Application.warmStudyCache(study: StudyDataService?) {
    if (study == null || !study.isConfigured) return
    if (System.getenv("PRIME_CACHE_ON_START")?.toBooleanStrictOrNull() != true) return
    launch {
        runCatching { study.studyData() }
            .onSuccess {
                environment.log.info(
                    "ESV cache primed: ${it.headings.size} headings indexed (${study.esvCallCount} live ESV calls this process)"
                )
            }
            .onFailure { environment.log.warn("ESV cache priming failed (falling back to lazy load): ${it.message}") }
    }
}

/** Optionally bootstraps a global admin from ADMIN_EMAIL / ADMIN_PASSWORD env vars on first run. */
private fun seedAdminFromEnv(users: UserRepository) {
    val email = System.getenv("ADMIN_EMAIL") ?: return
    val password = System.getenv("ADMIN_PASSWORD") ?: return
    if (users.findByEmail(email) == null) {
        users.create(email, "Administrator", null, Passwords.hash(password), listOf(RoleGrant(Role.ADMIN)))
    }
}
