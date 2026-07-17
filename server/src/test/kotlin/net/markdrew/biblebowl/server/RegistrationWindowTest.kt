package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.server.data.DEFAULT_SEASON
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegistrationWindowTest {

    // Noon UTC = morning in America/Chicago, so the local date matches the UTC date.
    private fun noonUtc(date: String): Instant = Instant.parse("${date}T12:00:00Z")

    private val season = DEFAULT_SEASON.copy(
        registrationOpensOn = "2027-02-01",
        registrationClosesOn = "2027-03-15",
    )

    @Test
    fun unannouncedOpensDateMeansNotYetOpen() {
        val unannounced = DEFAULT_SEASON.copy(registrationOpensOn = null)
        assertEquals(RegistrationWindowState.NOT_YET_OPEN, unannounced.registrationWindowState(noonUtc("2027-02-10")))
    }

    @Test
    fun windowIsInclusiveOfBothEndDays() {
        assertEquals(RegistrationWindowState.NOT_YET_OPEN, season.registrationWindowState(noonUtc("2027-01-31")))
        assertEquals(RegistrationWindowState.OPEN, season.registrationWindowState(noonUtc("2027-02-01")))
        assertEquals(RegistrationWindowState.OPEN, season.registrationWindowState(noonUtc("2027-03-15")))
        assertEquals(RegistrationWindowState.CLOSED, season.registrationWindowState(noonUtc("2027-03-16")))
    }

    @Test
    fun missingCloseDateLeavesRegistrationOpen() {
        val openEnded = season.copy(registrationClosesOn = null)
        assertEquals(RegistrationWindowState.OPEN, openEnded.registrationWindowState(noonUtc("2028-01-01")))
    }

    @Test
    fun closeDateIsEndOfDayInTexas() {
        // 04:00 UTC on Mar 16 is still 11 PM Mar 15 in America/Chicago (CDT) — window still open.
        assertEquals(RegistrationWindowState.OPEN, season.registrationWindowState(Instant.parse("2027-03-16T04:00:00Z")))
        // 06:00 UTC on Mar 16 is 1 AM Mar 16 in Chicago — closed.
        assertEquals(RegistrationWindowState.CLOSED, season.registrationWindowState(Instant.parse("2027-03-16T06:00:00Z")))
    }

    @Test
    fun parsesOnlyStrictIsoDates() {
        assertEquals("2027-02-01", parseIsoDateOrNull("2027-02-01")?.toString())
        assertNull(parseIsoDateOrNull("in February"))
        assertNull(parseIsoDateOrNull("02/01/2027"))
    }
}
