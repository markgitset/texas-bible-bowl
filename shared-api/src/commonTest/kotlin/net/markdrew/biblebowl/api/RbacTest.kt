package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.markdrew.biblebowl.model.Round

class RbacTest {

    @Test
    fun adminHasEveryPermission() {
        assertEquals(Permission.entries.toSet(), permissionsFor(listOf(Role.ADMIN)))
    }

    @Test
    fun contestantCannotModerateButCanSubmit() {
        val perms = permissionsFor(listOf(Role.CONTESTANT))
        assertTrue(Permission.QUESTION_SUBMIT in perms)
        assertFalse(Permission.QUESTION_MODERATE in perms)
        assertFalse(Permission.SCORE_ENTER in perms)
    }

    @Test
    fun stackedRolesUnionPermissions() {
        val perms = permissionsFor(listOf(Role.CONTESTANT, Role.COACH))
        assertTrue(Permission.TEAM_MANAGE in perms)
        assertTrue(Permission.QUESTION_SUBMIT in perms)
    }

    @Test
    fun graderCanEnterAndReleaseScores() {
        val perms = permissionsFor(listOf(Role.GRADER))
        assertTrue(Permission.SCORE_ENTER in perms)
        assertTrue(Permission.SCORE_RELEASE in perms)
    }

    @Test
    fun globalAdminHasEventWideRegistrationManage() {
        assertTrue(hasEventWidePermission(listOf(RoleGrant(Role.ADMIN)), Permission.REGISTRATION_MANAGE))
    }

    @Test
    fun registrarHasEventWideRegistrationManage() {
        assertTrue(hasEventWidePermission(listOf(RoleGrant(Role.REGISTRAR)), Permission.REGISTRATION_MANAGE))
    }

    @Test
    fun congregationScopedCoachIsNotEventWide() {
        // COACH's permission union contains REGISTRATION_MANAGE, but the grant is congregation-scoped.
        val coach = RoleGrant(Role.COACH, ScopeType.CONGREGATION, "c1")
        assertTrue(Permission.REGISTRATION_MANAGE in permissionsFor(listOf(Role.COACH)))
        assertFalse(hasEventWidePermission(listOf(coach), Permission.REGISTRATION_MANAGE))
    }

    @Test
    fun eventScopeWithoutThePermissionIsNotEnough() {
        assertFalse(hasEventWidePermission(listOf(RoleGrant(Role.GRADER)), Permission.REGISTRATION_MANAGE))
        assertFalse(hasEventWidePermission(listOf(RoleGrant(Role.CONTESTANT)), Permission.REGISTRATION_MANAGE))
    }

    @Test
    fun mixedGrantsPassIfAnyIsEventWide() {
        val roles = listOf(RoleGrant(Role.COACH, ScopeType.CONGREGATION, "c1"), RoleGrant(Role.REGISTRAR))
        assertTrue(hasEventWidePermission(roles, Permission.REGISTRATION_MANAGE))
    }
}

class DomainTest {

    @Test
    fun gradeMapsToDivision() {
        assertEquals(Division.ELEMENTARY, Division.forGrade(4))
        assertEquals(Division.JUNIOR, Division.forGrade(8))
        assertEquals(Division.SENIOR, Division.forGrade(11))
        assertEquals(null, Division.forGrade(1))
    }

    @Test
    fun elementaryHasNoPowerRound() {
        assertFalse(Division.ELEMENTARY.hasPowerRound)
        assertTrue(Division.SENIOR.hasPowerRound)
    }

    @Test
    fun closedBibleRoundsAreFourAndFive() {
        assertFalse(Round.QUOTES.openBible)
        assertFalse(Round.EVENTS.openBible)
        assertTrue(Round.FIND_THE_VERSE.openBible)
    }
}
