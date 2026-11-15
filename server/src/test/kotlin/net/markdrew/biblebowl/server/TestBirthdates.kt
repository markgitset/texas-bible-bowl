package net.markdrew.biblebowl.server

import java.time.LocalDate

/**
 * A birthdate for a test account whose holder is [age] today. Accounts have a minimum age measured
 * against the day the request arrives (see `isOldEnoughForAccount`), so a test that signs a youth
 * up must derive the birthdate from the run date — a fixed date only passes until the calendar
 * moves past it, or (for an under-age case) only until the child grows up.
 *
 * Roster and guest birthdates are a different matter: divisions are measured against the season's
 * fixed event year, so those stay literal dates.
 */
fun accountBirthdate(age: Int = 14): String = LocalDate.now().minusYears(age.toLong()).toString()
