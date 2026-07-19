package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.CreateCongregationRequest
import net.markdrew.biblebowl.api.Gender
import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpdateCongregationRequest
import net.markdrew.biblebowl.api.UpsertIndividualRequest
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.AssignResult
import net.markdrew.biblebowl.server.data.ClaimCodes
import net.markdrew.biblebowl.server.data.ClaimResult
import net.markdrew.biblebowl.server.data.InMemoryCongregationRepository
import net.markdrew.biblebowl.server.data.InMemoryRegistrationRepository
import net.markdrew.biblebowl.server.data.CreateCongregationResult
import net.markdrew.biblebowl.server.data.MAX_TEAM_SIZE
import net.markdrew.biblebowl.server.data.UpdateCongregationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationRepositoryTest {

    private val congregations = InMemoryCongregationRepository()
    private val repo = InMemoryRegistrationRepository(congregations)

    private fun entry(name: String, inexperienced: Boolean = false) = UpsertRosterEntryRequest(
        name, birthdate = "2014-05-01", shirtSize = ShirtSize.AM, gender = Gender.MALE, inexperienced = inexperienced,
    )

    private fun newCongregation(name: String, city: String, userId: String = "u1") =
        (congregations.create(
            CreateCongregationRequest(name, city, state = "TX", mailingAddress = "123 Main St", zip = "78701"),
            userId,
        ) as? CreateCongregationResult.Created)?.congregation

    @Test
    fun congregationNamesAreUniquePerCity() {
        assertNotNull(newCongregation("First Church", "Austin"))
        assertNull(newCongregation("  first church ", "AUSTIN", "u2"), "case/space-insensitive dupe")
        assertNotNull(newCongregation("First Church", "Dallas", "u2"), "same name, different city is fine")
        assertEquals(2, congregations.search("church").size)
        assertEquals(1, congregations.search("dallas").size)
    }

    @Test
    fun codesAreSuggestedFromTheNameAndUniqueAtCreation() {
        // The suggestion follows the initials convention.
        assertEquals("WB", congregations.suggestCode("West Bexar County Church of Christ"))

        val wb = assertIs<CreateCongregationResult.Created>(
            congregations.create(
                CreateCongregationRequest("West Bexar County Church of Christ", "San Antonio", state = "TX", mailingAddress = "1 St", zip = "78254", code = "wb"),
                "u1",
            ),
        ).congregation
        assertEquals("WB", wb.code, "the code is stored uppercased")

        // With WB taken, the next suggestion for the same name moves on to WC.
        assertEquals("WC", congregations.suggestCode("West Bexar County Church of Christ"))

        // Another congregation can't grab WB (case-insensitively).
        assertIs<CreateCongregationResult.CodeTaken>(
            congregations.create(
                CreateCongregationRequest("Westside Baptist", "Dallas", state = "TX", mailingAddress = "2 St", zip = "75001", code = "wb"),
                "u2",
            ),
        )
    }

    @Test
    fun updatingACongregationEditsFieldsAndEnforcesUniqueness() {
        val cong = newCongregation("First Church", "Austin")!!
        val other = newCongregation("Second Church", "Dallas", "u2")!!

        // Name, city, and state are all freely editable; state and code are stored uppercased.
        val updated = assertIs<UpdateCongregationResult.Updated>(
            congregations.update(
                cong.id,
                UpdateCongregationRequest(
                    "First Christian Church", "Round Rock", state = "ok",
                    mailingAddress = "456 Oak Ave", zip = "78664", code = "fc",
                ),
            ),
        ).congregation
        assertEquals("First Christian Church", updated.name)
        assertEquals("Round Rock", updated.city)
        assertEquals("OK", updated.state, "state is editable and normalized to uppercase")
        assertEquals("456 Oak Ave", updated.mailingAddress)
        assertEquals("78664", updated.zip)
        assertEquals("FC", updated.code, "code is stored uppercased")
        assertEquals(updated, congregations.findById(cong.id))

        // The two uniqueness constraints are reported distinctly.
        assertIs<UpdateCongregationResult.NameCityTaken>(
            congregations.update(cong.id, UpdateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "1 St", zip = "75001")),
        )
        congregations.update(other.id, UpdateCongregationRequest("Second Church", "Dallas", state = "TX", mailingAddress = "2 Elm St", zip = "75001", code = "SC"))
        assertIs<UpdateCongregationResult.CodeTaken>(
            congregations.update(cong.id, UpdateCongregationRequest("First Christian Church", "Round Rock", state = "OK", mailingAddress = "456 Oak Ave", zip = "78664", code = "sc")),
        )
        // Re-saving a congregation's own code is fine (self is excluded from the collision check).
        assertIs<UpdateCongregationResult.Updated>(
            congregations.update(cong.id, UpdateCongregationRequest("First Christian Church", "Round Rock", state = "OK", mailingAddress = "456 Oak Ave", zip = "78664", code = "FC")),
        )
        assertIs<UpdateCongregationResult.NotFound>(
            congregations.update("nope", UpdateCongregationRequest("Ghost", "Nowhere", state = "TX", mailingAddress = "0 St", zip = "00000")),
        )
    }

    @Test
    fun rosterIsCappedAtFourMembers() {
        val cong = newCongregation("Cap Church", "Waco")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        repeat(MAX_TEAM_SIZE) {
            assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Kid $it")))
        }
        assertIs<AddMemberResult.RosterFull>(repo.addMember(team.id, entry("Fifth Wheel")))
        // Deleting one frees a slot again.
        val someMember = repo.find(cong.id, "2027")!!.teams.single().members.first()
        assertTrue(repo.deleteMember(someMember.id))
        assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Sub In")))
    }

    @Test
    fun teamNamesAreUniqueWithinARegistration() {
        val cong = newCongregation("Dupe Church", "Tyler")!!
        assertNotNull(repo.addTeam(cong.id, "2027", "Alpha"))
        assertNull(repo.addTeam(cong.id, "2027", " alpha "))
        assertNotNull(repo.addTeam(cong.id, "2027", "Beta"))
    }

    @Test
    fun claimCodesAreUniqueAndWellFormed() {
        val cong = newCongregation("Code Church", "Amarillo")!!
        val codes = mutableSetOf<String>()
        repeat(50) { i ->
            val team = repo.addTeam(cong.id, "2027", "Team $i")!!
            repeat(4) { j ->
                val added = repo.addMember(team.id, entry("Kid $i-$j"))
                val code = assertIs<AddMemberResult.Added>(added).entry.claimCode
                assertEquals(ClaimCodes.LENGTH, code.length)
                assertTrue(code.all { it in "23456789ABCDEFGHJKMNPQRSTVWXYZ" }, "unexpected char in $code")
                assertTrue(codes.add(code), "duplicate claim code $code")
            }
        }
        assertEquals(200, codes.size)
    }

    @Test
    fun submitIsIdempotentAndRegistrationResumes() {
        val cong = newCongregation("Submit Church", "El Paso")!!
        assertNull(repo.find(cong.id, "2027"), "no registration until a team is added")
        assertNull(repo.submit(cong.id, "2027"), "nothing to submit yet")
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        repo.addMember(team.id, entry("Kid"))
        assertEquals(RegistrationStatus.DRAFT, repo.find(cong.id, "2027")!!.status)

        val submitted = repo.submit(cong.id, "2027")!!
        assertEquals(RegistrationStatus.SUBMITTED, submitted.status)
        assertNotNull(submitted.submittedAt)

        // Editing after submit keeps SUBMITTED; a re-submit still succeeds.
        repo.addMember(team.id, entry("Late Add"))
        assertEquals(RegistrationStatus.SUBMITTED, repo.find(cong.id, "2027")!!.status)
        assertNotNull(repo.submit(cong.id, "2027"))
    }

    @Test
    fun scopingLookupsResolveTheCongregation() {
        val cong = newCongregation("Scope Church", "Abilene")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        val member = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Kid"))).entry
        assertEquals(cong.id, repo.congregationIdForTeam(team.id))
        assertEquals(cong.id, repo.congregationIdForMember(member.id))
        assertNull(repo.congregationIdForTeam("nope"))
    }

    @Test
    fun individualsLiveOnTheRegistrationNotOnAnyTeam() {
        val cong = newCongregation("Individual Church", "Odessa")!!
        // Adding an individual creates the draft registration — no team required at all.
        val adult = repo.addIndividual(cong.id, "2027", UpsertIndividualRequest("Pat Adult", ShirtSize.AXL, Gender.FEMALE))
        assertNull(adult.birthdate, "individuals never carry a birthdate")
        assertEquals(Gender.FEMALE, adult.gender)
        assertNull(adult.firstSeasonYear, "the Adult division has no experience split")
        assertEquals(ClaimCodes.LENGTH, adult.claimCode.length)

        val reg = repo.find(cong.id, "2027")!!
        assertEquals(0, reg.teams.size)
        assertEquals(listOf("Pat Adult"), reg.individuals.map { it.name })
        assertEquals(cong.id, repo.congregationIdForIndividual(adult.id))
        assertNotNull(repo.submit(cong.id, "2027"), "an adults-only registration is submittable")

        val updated = repo.updateIndividual(adult.id, UpsertIndividualRequest("Pat A.", ShirtSize.AM, Gender.FEMALE))!!
        assertEquals("Pat A." to ShirtSize.AM, updated.name to updated.shirtSize)
        assertTrue(repo.deleteIndividual(adult.id))
        assertNull(repo.congregationIdForIndividual(adult.id))
        assertEquals(0, repo.find(cong.id, "2027")!!.individuals.size)
    }

    @Test
    fun listForSeasonReturnsOnlyThatSeasonsRegistrationsInFull() {
        val a = newCongregation("List Church A", "Waco")!!
        val b = newCongregation("List Church B", "Hutto")!!
        val team = repo.addTeam(a.id, "2027", "Team A")!!
        repo.addMember(team.id, entry("Kid"))
        repo.addIndividual(a.id, "2027", UpsertIndividualRequest("Pat Adult", ShirtSize.AXL, Gender.FEMALE))
        repo.addTeam(b.id, "2026", "Old Team")

        val listed = repo.listForSeason("2027")
        assertEquals(listOf(a.id), listed.map { it.congregation.id })
        assertEquals(1, listed.single().teams.single().members.size)
        assertEquals(1, listed.single().individuals.size)
        assertEquals(listOf(b.id), repo.listForSeason("2026").map { it.congregation.id })
    }

    @Test
    fun setPaidSetsAndClearsThePaymentTimestamp() {
        val cong = newCongregation("Paid Church", "Temple")!!
        val regId = repo.addTeam(cong.id, "2027", "Team A")!!.let { repo.find(cong.id, "2027")!!.id }
        assertNull(repo.find(cong.id, "2027")!!.paidAt)

        val paid = repo.setPaid(regId, 1_700_000_000_000L)!!
        assertNotNull(paid.paidAt)
        val cleared = repo.setPaid(regId, null)!!
        assertNull(cleared.paidAt)
        assertNull(repo.setPaid("nope", 1L), "unknown registration id")
    }

    @Test
    fun firstSeasonYearComesFromTheCheckboxOnFirstSight() {
        val cong = newCongregation("Rookie Church", "Waco")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        val rookie = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Ruth Rookie", inexperienced = true))).entry
        assertEquals("2027", rookie.firstSeasonYear)
        assertEquals(Gender.MALE, rookie.gender)
        val veteran = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Vera Veteran"))).entry
        assertNull(veteran.firstSeasonYear, "experienced with unknown first year")

        // Unchecking the box within the same season clears it again (no prior-season history).
        val edited = repo.updateMember(rookie.id, entry("Ruth Rookie", inexperienced = false))!!
        assertNull(edited.firstSeasonYear)
    }

    @Test
    fun priorSeasonRosterMakesAContestantExperiencedAutomatically() {
        val cong = newCongregation("History Church", "Waco")!!
        val lastYear = repo.addTeam(cong.id, "2027", "Team A")!!
        repo.addMember(lastYear.id, entry("Timothy", inexperienced = true))

        // Next season the coach re-enters the same contestant (case/space-insensitively) and
        // checks "first year" again — the 2027 roster wins and keeps their first year at 2027.
        val thisYear = repo.addTeam(cong.id, "2028", "Team A")!!
        val timothy = assertIs<AddMemberResult.Added>(repo.addMember(thisYear.id, entry("  TIMOTHY ", inexperienced = true))).entry
        assertEquals("2027", timothy.firstSeasonYear)

        // A genuinely new name is taken at the coach's word.
        val newKid = assertIs<AddMemberResult.Added>(repo.addMember(thisYear.id, entry("New Kid", inexperienced = true))).entry
        assertEquals("2028", newKid.firstSeasonYear)

        // A different congregation's history doesn't leak over.
        val other = newCongregation("Other Church", "Tyler", "u2")!!
        val otherTeam = repo.addTeam(other.id, "2028", "Team B")!!
        val otherTimothy = assertIs<AddMemberResult.Added>(repo.addMember(otherTeam.id, entry("Timothy", inexperienced = true))).entry
        assertEquals("2028", otherTimothy.firstSeasonYear)
    }

    @Test
    fun claimingLinksAnEntryAndIsIdempotentPerAccount() {
        val cong = newCongregation("Claim Church", "Waco")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        val added = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Kid"))).entry

        assertIs<ClaimResult.NotFound>(repo.claimEntry("ZZZZ9999", "owner-user"))
        val claimed = assertIs<ClaimResult.Claimed>(repo.claimEntry(added.claimCode, "owner-user"))
        assertTrue(claimed.entry.claimed)
        assertIs<ClaimResult.AlreadyClaimed>(repo.claimEntry(added.claimCode, "other-user"))
        assertIs<ClaimResult.Claimed>(repo.claimEntry(added.claimCode, "owner-user")) // idempotent re-claim
        assertEquals(setOf(added.id), repo.entryIdsOwnedBy("owner-user"))
        assertTrue(repo.entryIdsOwnedBy("other-user").isEmpty())
        // The claimed flag round-trips through the full registration read.
        assertTrue(repo.find(cong.id, "2027")!!.teams.single().members.single().claimed)
    }

    @Test
    fun deleteTeamFreesMembersToTheUnassignedPool() {
        val cong = newCongregation("Cascade Church", "Lubbock")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        val member = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Kid"))).entry
        assertTrue(repo.deleteTeam(team.id))

        val reg = repo.find(cong.id, "2027")!!
        assertEquals(0, reg.teams.size, "the team is gone")
        assertEquals(listOf("Kid"), reg.unassigned.map { it.name }, "but the contestant survives, unassigned")
        assertEquals(member.claimCode, reg.unassigned.single().claimCode, "same entry — claim code preserved")
        // Still scoped to its congregation even without a team, so permission checks resolve.
        assertEquals(cong.id, repo.congregationIdForMember(member.id))
    }

    @Test
    fun assignMovesContestantsBetweenTeamsAndOffToThePool() {
        val cong = newCongregation("Assign Church", "Killeen")!!
        val teamA = repo.addTeam(cong.id, "2027", "Team A")!!
        val teamB = repo.addTeam(cong.id, "2027", "Team B")!!
        val member = assertIs<AddMemberResult.Added>(repo.addMember(teamA.id, entry("Mover"))).entry

        // Move A -> B.
        assertIs<AssignResult.Assigned>(repo.assignMemberToTeam(member.id, teamB.id))
        repo.find(cong.id, "2027")!!.let { reg ->
            assertEquals(emptyList(), reg.teams.single { it.id == teamA.id }.members.map { it.name })
            assertEquals(listOf("Mover"), reg.teams.single { it.id == teamB.id }.members.map { it.name })
            assertTrue(reg.unassigned.isEmpty())
        }

        // Unassign (null team) -> pool.
        assertIs<AssignResult.Assigned>(repo.assignMemberToTeam(member.id, null))
        repo.find(cong.id, "2027")!!.let { reg ->
            assertTrue(reg.teams.all { it.members.isEmpty() })
            assertEquals(listOf("Mover"), reg.unassigned.map { it.name })
        }

        // Assign from the pool back onto a team.
        assertIs<AssignResult.Assigned>(repo.assignMemberToTeam(member.id, teamA.id))
        assertEquals(listOf("Mover"), repo.find(cong.id, "2027")!!.teams.single { it.id == teamA.id }.members.map { it.name })
    }

    @Test
    fun assignRejectsFullTeamsForeignTeamsAndUnknownMembers() {
        val cong = newCongregation("Full Church", "Bryan")!!
        val full = repo.addTeam(cong.id, "2027", "Full Team")!!
        repeat(MAX_TEAM_SIZE) { repo.addMember(full.id, entry("Starter $it")) }
        val loose = repo.addTeam(cong.id, "2027", "Spare Team")!!
        val member = assertIs<AddMemberResult.Added>(repo.addMember(loose.id, entry("Extra"))).entry
        assertIs<AssignResult.RosterFull>(repo.assignMemberToTeam(member.id, full.id))

        // A team in another congregation's registration is off-limits.
        val other = newCongregation("Other Church", "Georgetown", "u2")!!
        val otherTeam = repo.addTeam(other.id, "2027", "Their Team")!!
        assertIs<AssignResult.TeamNotFound>(repo.assignMemberToTeam(member.id, otherTeam.id))
        assertIs<AssignResult.MemberNotFound>(repo.assignMemberToTeam("nope", loose.id))
    }
}
