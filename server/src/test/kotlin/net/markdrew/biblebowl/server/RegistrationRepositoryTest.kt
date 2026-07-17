package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.api.RegistrationStatus
import net.markdrew.biblebowl.api.ShirtSize
import net.markdrew.biblebowl.api.UpsertRosterEntryRequest
import net.markdrew.biblebowl.server.data.AddMemberResult
import net.markdrew.biblebowl.server.data.ClaimCodes
import net.markdrew.biblebowl.server.data.InMemoryCongregationRepository
import net.markdrew.biblebowl.server.data.InMemoryRegistrationRepository
import net.markdrew.biblebowl.server.data.MAX_TEAM_SIZE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationRepositoryTest {

    private val congregations = InMemoryCongregationRepository()
    private val repo = InMemoryRegistrationRepository(congregations)

    private fun entry(name: String) = UpsertRosterEntryRequest(name, grade = 7, shirtSize = ShirtSize.AM)

    @Test
    fun congregationNamesAreUniquePerCity() {
        assertNotNull(congregations.create("First Church", "Austin", "u1"))
        assertNull(congregations.create("  first church ", "AUSTIN", "u2"), "case/space-insensitive dupe")
        assertNotNull(congregations.create("First Church", "Dallas", "u2"), "same name, different city is fine")
        assertEquals(2, congregations.search("church").size)
        assertEquals(1, congregations.search("dallas").size)
    }

    @Test
    fun rosterIsCappedAtFourMembers() {
        val cong = congregations.create("Cap Church", "Waco", "u1")!!
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
        val cong = congregations.create("Dupe Church", "Tyler", "u1")!!
        assertNotNull(repo.addTeam(cong.id, "2027", "Alpha"))
        assertNull(repo.addTeam(cong.id, "2027", " alpha "))
        assertNotNull(repo.addTeam(cong.id, "2027", "Beta"))
    }

    @Test
    fun claimCodesAreUniqueAndWellFormed() {
        val cong = congregations.create("Code Church", "Amarillo", "u1")!!
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
        val cong = congregations.create("Submit Church", "El Paso", "u1")!!
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
        val cong = congregations.create("Scope Church", "Abilene", "u1")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        val member = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Kid"))).entry
        assertEquals(cong.id, repo.congregationIdForTeam(team.id))
        assertEquals(cong.id, repo.congregationIdForMember(member.id))
        assertNull(repo.congregationIdForTeam("nope"))
    }

    @Test
    fun deleteTeamCascadesToMembers() {
        val cong = congregations.create("Cascade Church", "Lubbock", "u1")!!
        val team = repo.addTeam(cong.id, "2027", "Team A")!!
        val member = assertIs<AddMemberResult.Added>(repo.addMember(team.id, entry("Kid"))).entry
        assertTrue(repo.deleteTeam(team.id))
        assertNull(repo.congregationIdForMember(member.id))
        assertEquals(0, repo.find(cong.id, "2027")!!.teams.size)
    }
}
