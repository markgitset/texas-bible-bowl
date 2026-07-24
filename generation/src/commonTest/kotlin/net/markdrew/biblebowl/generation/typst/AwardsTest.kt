package net.markdrew.biblebowl.generation.typst

import kotlin.test.Test
import kotlin.test.assertTrue

class AwardsTest {

    @Test
    fun emitsRowsInGivenOrderWithEscapedNamesAndMemberLists() {
        val typ = awardsTypst(
            "Texas Bible Bowl 2027 — Awards",
            listOf(
                AwardSite(
                    heading = "Bandina",
                    brackets = listOf(
                        AwardBracket(
                            title = "Junior",
                            individuals = listOf(
                                AwardRow(2, "Ann \"A\" Bee", "Grace", "10 / 20"),
                                AwardRow(1, "Cee Dee", "Grace", "18 / 20"),
                            ),
                            teams = listOf(AwardRow(1, "Lions", "", "40 / 80", listOf("Ann Bee", "Cee Dee"))),
                        ),
                    ),
                ),
            ),
        )
        // Quotes in a name are escaped for the Typst string literal.
        assertTrue(typ.contains("""Ann \"A\" Bee"""), "name should be quote-escaped")
        // Team members are joined into one string.
        assertTrue(typ.contains("Ann Bee, Cee Dee"), "team members should be listed")
        // Rows appear in the order given (the caller sorts for reverse announcement order).
        assertTrue(typ.indexOf("Ann") < typ.indexOf("Cee Dee"), "place-2 row precedes place-1 row")
        // The site heading and a table helper are emitted.
        assertTrue(typ.contains("Bandina") && typ.contains("award_table"))
    }
}
