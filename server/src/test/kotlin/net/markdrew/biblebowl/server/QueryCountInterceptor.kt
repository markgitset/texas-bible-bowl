package net.markdrew.biblebowl.server

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only SQL statement counter, service-loaded by Exposed into every transaction (see
 * `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor`).
 * Guards the registration read paths against N+1 regressions — the statement count of a read
 * must not grow with roster or contestant size (PostgresRepositoryTest's query-count test).
 */
class QueryCountInterceptor : GlobalStatementInterceptor {

    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        count.incrementAndGet()
    }

    companion object {
        val count = AtomicInteger(0)

        /** Statements executed while running [block]. */
        fun measure(block: () -> Unit): Int {
            val before = count.get()
            block()
            return count.get() - before
        }
    }
}
