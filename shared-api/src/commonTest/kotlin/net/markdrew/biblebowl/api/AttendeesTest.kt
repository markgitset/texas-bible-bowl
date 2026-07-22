package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttendeesTest {

    /** FALLBACK_SEASON is the 2027 event, so the default grade cutoff is 2026-09-01. */
    private val season = FALLBACK_SEASON

    /** A contestant participant. [congregationId] null = home ("c1"); a value marks a visiting member. */
    private fun entry(
        name: String,
        birthdate: String?,
        firstSeasonYear: String? = null,
        congregationId: String? = null,
        gender: Gender? = Gender.MALE,
    ) = ParticipantDto(
        person = PersonDto(
            id = "p-$name", name = name, birthdate = birthdate, isAdult = birthdate == null,
            gender = gender, firstSeasonYear = firstSeasonYear, claimCode = "AAAA2222",
        ),
        participation = ParticipationDto(
            id = "e-$name", seasonYear = season.eventYear.toString(),
            congregationId = congregationId ?: "c1",
            congregationName = if (congregationId == null) "First Street" else "Elsewhere",
            isContestant = true, shirtSize = ShirtSize.AM,
        ),
    )

    /** A guest participant (isContestant = false). */
    private fun guest(
        id: String,
        name: String,
        shirtSize: ShirtSize? = null,
        birthdate: String? = null,
        gender: Gender? = null,
    ) = ParticipantDto(
        person = PersonDto(id = "p-$id", name = name, birthdate = birthdate, isAdult = birthdate == null, gender = gender),
        participation = ParticipationDto(
            id = id, seasonYear = season.eventYear.toString(), congregationId = "c1",
            congregationName = "First Street", isContestant = false, shirtSize = shirtSize,
        ),
    )

    private fun deskRow(registration: RegistrationDto?) = RegistrationDeskRowDto(
        congregation = CongregationDto(id = "c1", name = "First Street", city = "Waco"),
        registration = registration,
    )

    private fun registration(
        teams: List<TeamDto> = emptyList(),
        individuals: List<ParticipantDto> = emptyList(),
        unassigned: List<ParticipantDto> = emptyList(),
        awayMembers: List<AwayMemberDto> = emptyList(),
        guests: List<ParticipantDto> = emptyList(),
        siteId: String? = null,
    ) = RegistrationDto(
        id = "r1",
        congregation = CongregationDto(id = "c1", name = "First Street", city = "Waco"),
        seasonYear = season.eventYear.toString(),
        status = RegistrationStatus.SUBMITTED,
        siteId = siteId,
        teams = teams,
        individuals = individuals,
        unassigned = unassigned,
        awayMembers = awayMembers,
        guests = guests,
    )

    @Test
    fun everyRegisteredPersonAppearsOnceAndVisitingMembersDoNot() {
        // A team of two home members plus one visiting (combo) member, one unassigned youth,
        // one member away on another congregation's team, one adult individual, and two guests.
        val reg = registration(
            teams = listOf(
                TeamDto(
                    id = "t1", name = "Alpha",
                    members = listOf(
                        entry("Home A", "2012-05-01"),
                        entry("Home B", "2011-05-01"),
                        entry("Visitor", "2012-06-01", congregationId = "c9"),
                    ),
                )
            ),
            unassigned = listOf(entry("Loose Kid", "2018-05-01")),
            awayMembers = listOf(
                AwayMemberDto(entry("Away Kid", "2010-05-01"), teamId = "t9", teamName = "Combo", congregationName = "Elsewhere")
            ),
            individuals = listOf(entry("Adult Solo", birthdate = null)),
            guests = listOf(
                guest(id = "g1", name = "Grown Guest", shirtSize = ShirtSize.AL, gender = Gender.FEMALE),
                guest(id = "g2", name = "Toddler", shirtSize = null, birthdate = "2025-01-01", gender = Gender.MALE),
            ),
            siteId = "site-b",
        )
        val attendees = deskAttendees(season, listOf(deskRow(reg), deskRow(null)))

        assertEquals(
            listOf("Home A", "Home B", "Loose Kid", "Away Kid", "Adult Solo", "Grown Guest", "Toddler"),
            attendees.map { it.name },
            "each registered person once; the visiting member counts at their home congregation",
        )
        assertEquals(5, attendees.count { it.tester })
        assertEquals(2, attendees.count { !it.tester })
        assertTrue(attendees.all { it.siteId == "site-b" }, "the registration's site pin propagates")
        assertTrue(attendees.all { it.congregationName == "First Street" })
    }

    @Test
    fun testerFieldsDeriveFromBirthdateAndFirstSeason() {
        val reg = registration(
            unassigned = listOf(entry("Third Grader", "2018-05-01", firstSeasonYear = season.eventYear.toString())),
            individuals = listOf(entry("Adult Solo", birthdate = null)),
        )
        val (kid, adult) = deskAttendees(season, listOf(deskRow(reg)))

        assertEquals(3, kid.grade)
        assertEquals(Division.ELEMENTARY, kid.division)
        assertEquals(AgeTier.AGE_3_TO_8, kid.ageTier, "an 8-year-old contestant bills at the child tier")
        assertTrue(kid.inexperienced)

        assertNull(adult.grade)
        assertEquals(Division.ADULT, adult.division)
        assertEquals(AgeTier.AGE_9_PLUS, adult.ageTier)
        assertFalse(adult.inexperienced)
    }

    @Test
    fun guestFieldsDeriveTierFromBirthdate() {
        val reg = registration(
            guests = listOf(
                guest(id = "g1", name = "Grown Guest", shirtSize = ShirtSize.AL, gender = Gender.FEMALE),
                guest(id = "g2", name = "Toddler", shirtSize = null, birthdate = "2025-01-01"),
            ),
        )
        val (grown, toddler) = deskAttendees(season, listOf(deskRow(reg)))

        assertFalse(grown.tester)
        assertEquals(AgeTier.AGE_9_PLUS, grown.ageTier, "no birthdate collected → adult guest")
        assertNull(grown.division)
        assertNull(grown.grade)
        assertEquals(AgeTier.UNDER_3, toddler.ageTier)
        assertNull(toddler.shirtSize)
    }

    @Test
    fun graduatingSeniorsAreGradeTwelveTestersOnly() {
        val reg = registration(
            unassigned = listOf(
                entry("Senior Sam", "2009-05-01"), // 17 on the cutoff → grade 12
                entry("Junior Jo", "2012-05-01"), // grade 9
            ),
            individuals = listOf(entry("Adult Solo", birthdate = null)),
        )
        val seniors = deskAttendees(season, listOf(deskRow(reg))).graduatingSeniors()
        assertEquals(listOf("Senior Sam"), seniors.map { it.name })
    }
}
