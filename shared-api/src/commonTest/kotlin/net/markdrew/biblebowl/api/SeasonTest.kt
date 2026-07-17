package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonTest {

    @Test
    fun formatsCentsAsDollars() {
        assertEquals("TBD", formatCents(null))
        assertEquals("$0", formatCents(0))
        assertEquals("$85", formatCents(8500))
        assertEquals("$12.50", formatCents(1250))
        assertEquals("$9.05", formatCents(905))
    }

    @Test
    fun formatsIsoDatesForDisplay() {
        assertEquals("TBD", formatIsoDate(null))
        assertEquals("February 1, 2027", formatIsoDate("2027-02-01"))
        assertEquals("December 31, 2026", formatIsoDate("2026-12-31"))
        // Unparseable input falls through as-is rather than crashing.
        assertEquals("sometime soon", formatIsoDate("sometime soon"))
    }

    @Test
    fun feesNoteFollowsTheTentativeFlag() {
        assertEquals("Prices are tentative and subject to change.", FALLBACK_SEASON.feesNote)
        assertEquals("", FALLBACK_SEASON.copy(feesTentative = false).feesNote)
    }
}
