package net.markdrew.biblebowl.server.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Dependency-free password hashing using PBKDF2-HMAC-SHA256 (JDK built-in).
 *
 * Stored format: `iterations:saltBase64:hashBase64`. This is adequate for launch; a follow-up may swap in
 * Argon2id (see the plan's hardening notes) behind the same [hash]/[verify] surface.
 */
object Passwords {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = pbkdf2(password.toCharArray(), salt, ITERATIONS)
        val enc = Base64.getEncoder()
        return "$ITERATIONS:${enc.encodeToString(salt)}:${enc.encodeToString(hash)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = dec.decode(parts[1])
        val expected = dec.decode(parts[2])
        val actual = pbkdf2(password.toCharArray(), salt, iterations)
        return constantTimeEquals(expected, actual)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }
}
