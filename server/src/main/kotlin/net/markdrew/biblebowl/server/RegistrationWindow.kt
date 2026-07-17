package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.SeasonDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** The event's home time zone: registration dates are interpreted in Texas local time. */
val TBB_ZONE: ZoneId = ZoneId.of("America/Chicago")

/** Parses a strict ISO-8601 date ("2027-02-01"), or null if it doesn't parse. */
fun parseIsoDateOrNull(iso: String): LocalDate? =
    try {
        LocalDate.parse(iso)
    } catch (_: DateTimeParseException) {
        null
    }

enum class RegistrationWindowState { NOT_YET_OPEN, OPEN, CLOSED }

/**
 * Where [now] falls in this season's registration window. An unset/unparseable opens-date means
 * registration hasn't been announced ([NOT_YET_OPEN]); the closes-date is inclusive through the
 * end of that day in [TBB_ZONE]; an unset closes-date leaves registration open indefinitely.
 */
fun SeasonDto.registrationWindowState(now: Instant = Instant.now()): RegistrationWindowState {
    val opens = registrationOpensOn?.let { parseIsoDateOrNull(it) } ?: return RegistrationWindowState.NOT_YET_OPEN
    val today = LocalDate.ofInstant(now, TBB_ZONE)
    if (today < opens) return RegistrationWindowState.NOT_YET_OPEN
    val closes = registrationClosesOn?.let { parseIsoDateOrNull(it) }
    if (closes != null && today > closes) return RegistrationWindowState.CLOSED
    return RegistrationWindowState.OPEN
}
