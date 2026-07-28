package net.markdrew.biblebowl.server.data

import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * A user's single active password-reset code (see AuthRoutes): only the PBKDF2 hash of the emailed
 * 6-digit code is stored, with a hard expiry and a wrong-guess counter — the route invalidates the
 * code once [attempts] hits its cap, so 6 digits can't be brute-forced.
 */
data class ResetCode(
    val userId: String,
    val codeHash: String,
    val expiresAtEpochMs: Long,
    val attempts: Int = 0,
)

interface PasswordResetRepository {
    /** Upserts the user's single active code (a re-request replaces the old code, attempts reset). */
    fun save(code: ResetCode)
    fun find(userId: String): ResetCode?
    /** Records a wrong guess; returns the new attempt count (0 when no code is active). */
    fun incrementAttempts(userId: String): Int
    fun delete(userId: String)
}

class InMemoryPasswordResetRepository : PasswordResetRepository {
    private val byUser = ConcurrentHashMap<String, ResetCode>()

    override fun save(code: ResetCode) {
        byUser[code.userId] = code
    }

    override fun find(userId: String): ResetCode? = byUser[userId]

    override fun incrementAttempts(userId: String): Int =
        byUser.computeIfPresent(userId) { _, code -> code.copy(attempts = code.attempts + 1) }?.attempts ?: 0

    override fun delete(userId: String) {
        byUser.remove(userId)
    }
}

class PostgresPasswordResetRepository(private val db: Database) : PasswordResetRepository {

    override fun save(code: ResetCode): Unit = transaction(db) {
        // Inside Exposed lambdas the table columns are the implicit receiver, so an unqualified
        // `userId` is the COLUMN — bind function parameters to distinct local names first.
        PasswordResetCodesTable.deleteWhere { userId eq code.userId }
        PasswordResetCodesTable.insert {
            it[userId] = code.userId
            it[codeHash] = code.codeHash
            it[expiresAtEpochMs] = code.expiresAtEpochMs
            it[attempts] = code.attempts
        }
    }

    override fun find(userId: String): ResetCode? {
        val uid = userId
        return transaction(db) {
            PasswordResetCodesTable.selectAll()
                .where { PasswordResetCodesTable.userId eq uid }
                .singleOrNull()
                ?.let {
                    ResetCode(
                        userId = it[PasswordResetCodesTable.userId],
                        codeHash = it[PasswordResetCodesTable.codeHash],
                        expiresAtEpochMs = it[PasswordResetCodesTable.expiresAtEpochMs],
                        attempts = it[PasswordResetCodesTable.attempts],
                    )
                }
        }
    }

    override fun incrementAttempts(userId: String): Int {
        val uid = userId
        return transaction(db) {
            PasswordResetCodesTable.update({ PasswordResetCodesTable.userId eq uid }) {
                with(SqlExpressionBuilder) { it[attempts] = attempts + 1 }
            }
            find(uid)?.attempts ?: 0
        }
    }

    override fun delete(userId: String) {
        val uid = userId
        transaction(db) { PasswordResetCodesTable.deleteWhere { PasswordResetCodesTable.userId eq uid } }
    }
}
