package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationDomainTest {

    /** FALLBACK_SEASON is the 2027 event, so the default grade cutoff is 2026-09-01. */
    private val season = FALLBACK_SEASON

    private fun entry(birthdate: String?) =
        RosterEntryDto(id = "e$birthdate", name = "Kid", birthdate = birthdate, shirtSize = ShirtSize.AM, claimCode = "AAAA2222")

    @Test
    fun agesCountFullYearsWithBirthdayBoundaries() {
        assertEquals(8, ageOn("2018-05-01", "2026-09-01"))
        assertEquals(8, ageOn("2018-09-01", "2026-09-01"), "birthday on the cutoff day counts")
        assertEquals(7, ageOn("2018-09-02", "2026-09-01"), "birthday the day after doesn't")
        assertNull(ageOn("not-a-date", "2026-09-01"))
        assertNull(ageOn("2018-05-01", "soon"))
    }

    @Test
    fun gradeCutoffDefaultsToSeptemberFirstBeforeTheEvent() {
        assertEquals("2026-09-01", season.gradeCutoff)
        assertEquals("2026-08-15", season.copy(gradeCutoffDate = "2026-08-15").gradeCutoff, "explicit override wins")
        assertEquals("2026-09-01", season.copy(gradeCutoffDate = "").gradeCutoff, "blank falls back")
    }

    @Test
    fun birthdatesMapToGradesAndDivisions() {
        // Age on 2026-09-01 minus 5 = grade: 8 → grade 3, 14 → grade 9, 17 → grade 12.
        assertEquals(3, season.gradeForBirthdate("2018-05-01"))
        assertEquals(Division.ELEMENTARY, season.divisionForBirthdate("2018-05-01"))
        assertEquals(Division.JUNIOR, season.divisionForBirthdate("2012-05-01"))
        assertEquals(Division.SENIOR, season.divisionForBirthdate("2009-05-01"))
        assertEquals(Division.SENIOR, season.divisionForBirthdate("2008-09-02"), "17 on the cutoff → grade 12")
        assertEquals(Division.ADULT, season.divisionForBirthdate("2008-09-01"), "18 on the cutoff → past high school")
        assertEquals(Division.ADULT, season.divisionForBirthdate("2000-01-01"))
        assertNull(season.divisionForBirthdate("2022-01-01"), "too young to compete")
        assertNull(season.divisionForBirthdate("garbage"))
    }

    @Test
    fun userDivisionComesFromAdultFlagOrBirthdate() {
        val user = UserDto(id = "u", email = "a@b.c", displayName = "A")
        assertNull(user.division(season), "legacy profile: neither adult nor birthdate")
        assertEquals(Division.ADULT, user.copy(adult = true).division(season))
        assertEquals(Division.JUNIOR, user.copy(birthdate = "2012-05-01").division(season))
    }

    @Test
    fun rosterEntryDivisionFallsBackToAdult() {
        assertEquals(Division.ELEMENTARY, entry("2018-05-01").division(season))
        assertEquals(Division.JUNIOR, entry("2012-05-01").division(season))
        assertEquals(Division.SENIOR, entry("2009-05-01").division(season))
        assertEquals(Division.ADULT, entry(null).division(season))
    }

    @Test
    fun teamCompetesAtItsHighestMembersDivision() {
        assertNull(TeamDto("t", "Empty").division(season))
        val mixed = TeamDto("t", "Mixed", listOf(entry("2018-05-01"), entry("2013-05-01")))
        assertEquals(Division.JUNIOR, mixed.division(season))
        val withAdult = TeamDto("t", "With Adult", listOf(entry("2018-05-01"), entry(null)))
        assertEquals(Division.ADULT, withAdult.division(season))
    }

    @Test
    fun birthdateValidationIsStructural() {
        assertTrue(isValidBirthdate("2012-05-01"))
        assertFalse(isValidBirthdate("2012-13-01"), "no 13th month")
        assertFalse(isValidBirthdate("05/01/2012"))
        assertFalse(isValidBirthdate("1850-01-01"), "implausible year")
    }

    @Test
    fun registrationTotalUsesTheContestantFee() {
        val season = FALLBACK_SEASON.copy(priceContestantCents = 8500)
        assertEquals(25500, registrationTotalCents(season, 3))
        assertEquals(0, registrationTotalCents(season, 0))
        assertNull(registrationTotalCents(FALLBACK_SEASON, 3), "TBD fee → no total")
    }

    @Test
    fun claimCodesDisplayInGroupsOfFour() {
        assertEquals("ABCD-2345", formatClaimCode("ABCD2345"))
        assertEquals("SHORT", formatClaimCode("SHORT"), "non-8-char codes pass through")
    }

    @Test
    fun scopedPermissionRequiresMatchingCongregation() {
        val coachOfA = listOf(RoleGrant(Role.COACH, ScopeType.CONGREGATION, "cong-a"))
        assertTrue(hasScopedPermission(coachOfA, Permission.TEAM_MANAGE, "cong-a"))
        assertFalse(hasScopedPermission(coachOfA, Permission.TEAM_MANAGE, "cong-b"))
        assertFalse(hasScopedPermission(coachOfA, Permission.SEASON_MANAGE, "cong-a"), "permission not held at all")

        val admin = listOf(RoleGrant(Role.ADMIN))
        assertTrue(hasScopedPermission(admin, Permission.TEAM_MANAGE, "any-congregation"), "GLOBAL passes everywhere")

        val contestant = listOf(RoleGrant(Role.CONTESTANT))
        assertFalse(hasScopedPermission(contestant, Permission.TEAM_MANAGE, "cong-a"))
        // A dangling COACH grant with no scopeId matches nothing.
        val unscopedCoach = listOf(RoleGrant(Role.COACH, ScopeType.CONGREGATION, null))
        assertFalse(hasScopedPermission(unscopedCoach, Permission.TEAM_MANAGE, "cong-a"))
    }

    @Test
    fun coachedCongregationIdsCollectsOnlyScopedCoachGrants() {
        val roles = listOf(
            RoleGrant(Role.CONTESTANT),
            RoleGrant(Role.COACH, ScopeType.CONGREGATION, "cong-a"),
            RoleGrant(Role.COACH, ScopeType.CONGREGATION, "cong-b"),
            RoleGrant(Role.COACH, ScopeType.CONGREGATION, null),
            RoleGrant(Role.ADMIN),
        )
        assertEquals(listOf("cong-a", "cong-b"), coachedCongregationIds(roles))
    }
}
