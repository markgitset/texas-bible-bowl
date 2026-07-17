package net.markdrew.biblebowl.server.data

import java.security.SecureRandom

/**
 * Claim codes: 8 characters from an unambiguous alphabet (no 0/O, 1/I/L), giving a ~30^8 space —
 * unguessable at roster scale. A coach shares a roster entry's code so a contestant/parent account
 * can later claim that entry; codes are unique app-wide (DB unique index + bounded retry on the
 * astronomically unlikely collision).
 */
object ClaimCodes {
    const val LENGTH = 8
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
