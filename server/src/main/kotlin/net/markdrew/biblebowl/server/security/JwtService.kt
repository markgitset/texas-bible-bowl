package net.markdrew.biblebowl.server.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

/**
 * Issues and verifies the app's JWTs. One token = one account across web + mobile.
 *
 * Config comes from the environment so no secret is hard-coded in production:
 *   JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE. A dev fallback secret is used only when unset.
 */
class JwtService(
    val secret: String = System.getenv("JWT_SECRET") ?: "dev-only-insecure-secret-change-me",
    val issuer: String = System.getenv("JWT_ISSUER") ?: "texas-bible-bowl",
    val audience: String = System.getenv("JWT_AUDIENCE") ?: "tbb-app",
    private val ttlMillis: Long = 30L * 24 * 60 * 60 * 1000, // 30 days
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun issue(userId: String, email: String): String =
        JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + ttlMillis))
            .sign(algorithm)
}
