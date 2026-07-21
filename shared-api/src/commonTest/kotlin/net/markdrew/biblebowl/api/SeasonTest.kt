package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun sitesResolveByIdWithASingleSiteShortCircuit() {
        val bandina = EventSiteDto("s1", "Bandina")
        val whiteRiver = EventSiteDto("s2", "White River Youth Camp")
        val single = FALLBACK_SEASON.copy(sites = listOf(bandina))
        val multi = FALLBACK_SEASON.copy(sites = listOf(bandina, whiteRiver))

        assertFalse(FALLBACK_SEASON.multiSite)
        assertFalse(single.multiSite)
        assertTrue(multi.multiSite)

        // No sites: nothing to resolve.
        assertNull(FALLBACK_SEASON.siteFor("s1"))
        // A single-site season resolves its lone site no matter what was (or wasn't) chosen.
        assertEquals(bandina, single.siteFor(null))
        assertEquals(bandina, single.siteFor("stale-id"))
        // Multi-site: only a valid current id resolves — null or a removed site means "not chosen".
        assertEquals(whiteRiver, multi.siteFor("s2"))
        assertNull(multi.siteFor(null))
        assertNull(multi.siteFor("stale-id"))
    }

    @Test
    fun feesNoteFollowsTheTentativeFlag() {
        assertEquals("Prices are tentative and subject to change.", FALLBACK_SEASON.feesNote)
        assertEquals("", FALLBACK_SEASON.copy(feesTentative = false).feesNote)
    }
}
