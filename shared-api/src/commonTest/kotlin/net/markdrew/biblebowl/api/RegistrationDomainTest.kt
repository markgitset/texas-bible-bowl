package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationDomainTest {

    private fun entry(grade: Int?) =
        RosterEntryDto(id = "e$grade", name = "Kid", grade = grade, shirtSize = ShirtSize.AM, claimCode = "AAAA2222")

    @Test
    fun rosterEntryDivisionFallsBackToAdult() {
        assertEquals(Division.ELEMENTARY, entry(3).division)
        assertEquals(Division.JUNIOR, entry(9).division)
        assertEquals(Division.SENIOR, entry(12).division)
        assertEquals(Division.ADULT, entry(null).division)
    }

    @Test
    fun teamCompetesAtItsHighestMembersDivision() {
        assertNull(TeamDto("t", "Empty").division())
        val mixed = TeamDto("t", "Mixed", listOf(entry(3), entry(8)))
        assertEquals(Division.JUNIOR, mixed.division())
    }

    @Test
    fun contestantCountSpansTeamsAndIndividuals() {
        val reg = RegistrationDto(
            id = "r",
            congregation = CongregationDto("c", "First Church", "Austin"),
            seasonYear = "2027",
            status = RegistrationStatus.DRAFT,
            teams = listOf(TeamDto("t", "Team A", listOf(entry(3), entry(8)))),
            individuals = listOf(entry(null)),
        )
        assertEquals(3, reg.contestantCount)
        assertEquals(0, reg.copy(teams = emptyList(), individuals = emptyList()).contestantCount)
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
