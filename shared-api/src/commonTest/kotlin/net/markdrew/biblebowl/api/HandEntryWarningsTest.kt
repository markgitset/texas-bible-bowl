package net.markdrew.biblebowl.api

import net.markdrew.biblebowl.model.Round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandEntryWarningsTest {

    private fun row(testerId: Int?, name: String, scores: Map<Round, Int>) =
        ScoreRowDto(rosterEntryId = "r$testerId", testerId = testerId, contestantName = name, congregationName = "C", scores = scores)

    @Test
    fun flagsScoreEqualToTesterIdOnHandRounds() {
        val warnings = handEntryWarnings(listOf(row(7, "Mia", mapOf(Round.FIND_THE_VERSE to 7))))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Find the Verse"))
        assertTrue(warnings.single().contains("tester ID"))
    }

    @Test
    fun flagsVerseFindEqualsPowerAsDoublePaste() {
        val warnings = handEntryWarnings(listOf(row(3, "Noah", mapOf(Round.FIND_THE_VERSE to 40, Round.POWER to 40))))
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("double paste"))
    }

    @Test
    fun doesNotFlagNormalEntriesOrBlankCells() {
        // Distinct, sensible scores that don't equal the tester ID; and a blank row.
        val rows = listOf(
            row(3, "Ada", mapOf(Round.FIND_THE_VERSE to 38, Round.POWER to 45)),
            row(4, "Ben", emptyMap()),
            // A scanned round equal to the tester id is NOT a hand-entry flag (only R1/Power are).
            row(20, "Cal", mapOf(Round.FACT_FINDER to 20)),
        )
        assertTrue(handEntryWarnings(rows).isEmpty())
    }
}
