package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationDomainTest {

    /** FALLBACK_SEASON is the 2027 event, so the default grade cutoff is 2026-09-01. */
    private val season = FALLBACK_SEASON

    private fun entry(birthdate: String?, firstSeasonYear: String? = null) = RosterEntryDto(
        id = "e$birthdate",
        name = "Kid",
        birthdate = birthdate,
        shirtSize = ShirtSize.AM,
        firstSeasonYear = firstSeasonYear,
        claimCode = "AAAA2222",
    )

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
        val playUp = TeamDto("t", "PlayUp", listOf(entry("2018-05-01"), entry("2009-05-01")))
        assertEquals(Division.SENIOR, playUp.division(season))
    }

    @Test
    fun thereAreNoElementaryTeams() {
        // Elementary contestants may play up onto a team, but a team never competes below Junior.
        val allElementary = TeamDto("t", "Little Lions", listOf(entry("2018-05-01"), entry("2018-05-01")))
        assertEquals(Division.JUNIOR, allElementary.division(season))
    }

    @Test
    fun birthdateValidationIsStructural() {
        assertTrue(isValidBirthdate("2012-05-01"))
        assertFalse(isValidBirthdate("2012-13-01"), "no 13th month")
        assertFalse(isValidBirthdate("05/01/2012"))
        assertFalse(isValidBirthdate("1850-01-01"), "implausible year")
    }

    @Test
    fun inexperiencedMeansFirstSeasonIsTheCurrentOne() {
        assertTrue(entry("2018-05-01", firstSeasonYear = "2027").isInexperienced("2027"))
        assertFalse(entry("2018-05-01", firstSeasonYear = "2027").isInexperienced("2028"), "experienced the next year")
        assertFalse(entry("2018-05-01").isInexperienced("2027"), "null first season = experienced")
    }

    @Test
    fun teamIsInexperiencedOnlyWhenEveryMemberIs() {
        val rookie = entry("2018-05-01", firstSeasonYear = "2027")
        val veteran = entry("2013-05-01", firstSeasonYear = "2026")
        assertTrue(TeamDto("t", "Rookies", listOf(rookie, rookie)).isInexperienced("2027"))
        assertFalse(TeamDto("t", "Mixed", listOf(rookie, veteran)).isInexperienced("2027"))
        assertFalse(TeamDto("t", "Empty").isInexperienced("2027"))
    }

    @Test
    fun divisionLabelsSplitByExperienceExceptAdult() {
        assertEquals("Junior (Inexperienced)", divisionLabel(Division.JUNIOR, inexperienced = true))
        assertEquals("Junior", divisionLabel(Division.JUNIOR, inexperienced = false))
        assertEquals("Adult", divisionLabel(Division.ADULT, inexperienced = true), "no adult experience split")
    }

    @Test
    fun contestantCountSpansTeamsIndividualsAndUnassigned() {
        val reg = RegistrationDto(
            id = "r",
            congregation = CongregationDto("c", "First Church", "Austin"),
            seasonYear = "2027",
            status = RegistrationStatus.DRAFT,
            teams = listOf(TeamDto("t", "Team A", listOf(entry("2018-05-01"), entry("2013-05-01")))),
            individuals = listOf(entry(null)),
            unassigned = listOf(entry("2014-05-01")),
        )
        assertEquals(4, reg.contestantCount, "team members + individual + unassigned all count")
        assertEquals(
            0,
            reg.copy(teams = emptyList(), individuals = emptyList(), unassigned = emptyList()).contestantCount,
        )
    }

    @Test
    fun ageTiersDeriveFromBirthdatesOnTheGradeCutoff() {
        // FALLBACK_SEASON is the 2027 event: ages are computed on the 2026-09-01 grade cutoff.
        assertEquals(AgeTier.AGE_9_PLUS, season.ageTierFor(null), "no birthdate = adult")
        assertEquals(AgeTier.UNDER_3, season.ageTierFor("2024-06-01")) // age 2
        assertEquals(AgeTier.AGE_3_TO_8, season.ageTierFor("2023-09-01"), "turns 3 on the cutoff")
        assertEquals(AgeTier.AGE_3_TO_8, season.ageTierFor("2018-05-01"), "8 — a grade-3 contestant")
        assertEquals(AgeTier.AGE_9_PLUS, season.ageTierFor("2017-09-01"), "turns 9 on the cutoff")
        assertEquals(AgeTier.AGE_9_PLUS, season.ageTierFor("not-a-date"), "unparseable reads as adult")
    }

    @Test
    fun registrationTotalBillsEveryAttendeeByAgeTier() {
        val season = FALLBACK_SEASON.copy(
            priceContestantCents = 8500,
            priceVolunteerCents = 4000,
            priceChildCents = 2500,
        )
        val threeContestants = RegistrationDto(
            id = "r",
            congregation = CongregationDto("c", "First Church", "Austin"),
            seasonYear = "2027",
            status = RegistrationStatus.DRAFT,
            teams = listOf(TeamDto("t", "Team A", listOf(entry("2018-05-01"), entry("2013-05-01")))),
            individuals = listOf(entry(null)),
        )
        assertEquals(
            2500 + 8500 + 8500, registrationTotalCents(season, threeContestants),
            "an 8-year-old grade-3 contestant pays the child fee, like every attendee aged 3\u20138",
        )
        assertEquals(0, registrationTotalCents(season, threeContestants.copy(teams = emptyList(), individuals = emptyList())))
        assertNull(registrationTotalCents(FALLBACK_SEASON, threeContestants), "TBD fees \u2192 no total")

        val guests = listOf(
            GuestDto("g1", "Helpful Aunt", ShirtSize.AM, gender = Gender.FEMALE),
            GuestDto("g2", "Volunteer Uncle", ShirtSize.AL, gender = Gender.MALE),
            GuestDto("g3", "Little Sibling", ShirtSize.YS, birthdate = "2020-06-15", gender = Gender.MALE),
            GuestDto("g4", "Baby Sibling", null, birthdate = "2025-06-15", gender = Gender.FEMALE),
        )
        assertEquals(
            19500 + 2 * 4000 + 2500,
            registrationTotalCents(season, threeContestants.copy(guests = guests)),
            "9+ guests at the volunteer fee, 3\u20138 at the child fee, under-3s free",
        )
        assertEquals(
            listOf(
                FeeLine(AgeTier.AGE_9_PLUS, contestant = true, count = 2, eachCents = 8500),
                FeeLine(AgeTier.AGE_3_TO_8, contestant = true, count = 1, eachCents = 2500),
                FeeLine(AgeTier.AGE_9_PLUS, contestant = false, count = 2, eachCents = 4000),
                FeeLine(AgeTier.AGE_3_TO_8, contestant = false, count = 1, eachCents = 2500),
                FeeLine(AgeTier.UNDER_3, contestant = false, count = 1, eachCents = 0),
            ),
            registrationFeeLines(season, threeContestants.copy(guests = guests)),
            "the invoice lines group every attendee by tier",
        )
        assertNull(
            registrationTotalCents(season.copy(priceChildCents = null), threeContestants.copy(guests = guests)),
            "TBD fee for a tier in use \u2192 no total",
        )

        // A registration with only 9+ attendees isn't blocked by TBD child/volunteer fees…
        val nineUpOnly = threeContestants.copy(
            teams = listOf(TeamDto("t", "Team A", listOf(entry("2013-05-01")))),
        )
        assertEquals(
            2 * 8500,
            registrationTotalCents(season.copy(priceVolunteerCents = null, priceChildCents = null), nineUpOnly),
            "TBD fees for unused tiers don't block the total",
        )
        // …and under-3 guests are free, so they never need a price either.
        assertEquals(
            2 * 8500,
            registrationTotalCents(
                season.copy(priceVolunteerCents = null, priceChildCents = null),
                nineUpOnly.copy(guests = listOf(GuestDto("g4", "Baby Sibling", null, birthdate = "2025-06-15"))),
            ),
            "under-3 guests are free, so TBD fees don't block them either",
        )
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
