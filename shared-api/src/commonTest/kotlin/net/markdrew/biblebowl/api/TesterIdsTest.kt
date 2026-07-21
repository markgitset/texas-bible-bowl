package net.markdrew.biblebowl.api

import kotlin.test.Test
import kotlin.test.assertEquals

class TesterIdsTest {

    @Test
    fun indCatCodesMatchTheWorkbookLookup() {
        // The 2026 IndCat tab: AD, EI, EE, JI, JE, SI, SE.
        assertEquals("AD", indCatCode(Division.ADULT, inexperienced = false))
        assertEquals("AD", indCatCode(Division.ADULT, inexperienced = true)) // no adult split
        assertEquals("EI", indCatCode(Division.ELEMENTARY, inexperienced = true))
        assertEquals("EE", indCatCode(Division.ELEMENTARY, inexperienced = false))
        assertEquals("JI", indCatCode(Division.JUNIOR, inexperienced = true))
        assertEquals("JE", indCatCode(Division.JUNIOR, inexperienced = false))
        assertEquals("SI", indCatCode(Division.SENIOR, inexperienced = true))
        assertEquals("SE", indCatCode(Division.SENIOR, inexperienced = false))
    }

    @Test
    fun teamPartForTeamlessTesters() {
        // The workbook's "no teams" codes: ANT for adults, ENT for elementary; JNT/SNT extend the
        // pattern for youth a registrar hasn't placed yet.
        assertEquals("ANT", testerTeamPart(Division.ADULT, null, null, false))
        assertEquals("ENT", testerTeamPart(Division.ELEMENTARY, null, null, false))
        assertEquals("JNT", testerTeamPart(Division.JUNIOR, null, null, false))
        assertEquals("SNT", testerTeamPart(Division.SENIOR, null, null, false))
    }

    @Test
    fun teamPartFusesTeamCatWithSanitizedName() {
        // 2026 examples: JE + AI_AVENGERS → JEAI_AVENGERS; SE + MCLC-SE → SEMCLC-SE.
        assertEquals(
            "JEAI_AVENGERS",
            testerTeamPart(Division.JUNIOR, "AI Avengers", Division.JUNIOR, false),
        )
        assertEquals(
            "SEMCLC-SE",
            testerTeamPart(Division.JUNIOR, "MCLC-SE", Division.SENIOR, false),
        )
        // An elementary member playing up carries the TEAM's bracket in the team part.
        assertEquals(
            "JITHE_TITANS",
            testerTeamPart(Division.ELEMENTARY, "The Titans!", Division.JUNIOR, true),
        )
    }

    @Test
    fun sanitizeUppercasesUnderscoresAndDrops() {
        assertEquals("TENT-PEG_TITANS", sanitizeTeamNameForId("Tent-Peg Titans"))
        assertEquals("MRLC-COMBO", sanitizeTeamNameForId("MRLC-Combo"))
        assertEquals("GODS_TEAM", sanitizeTeamNameForId("  God's Team  "))
    }

    @Test
    fun externalIdMatchesWorkbookShape() {
        assertEquals(
            "EI-MR-ENT-4",
            externalTesterId(Division.ELEMENTARY, true, "MR", "ENT", 4),
        )
        assertEquals(
            "JI-MR-JEMRLC-COMBO-12",
            externalTesterId(
                Division.JUNIOR, true, "MR",
                testerTeamPart(Division.JUNIOR, "MRLC-Combo", Division.JUNIOR, false), 12,
            ),
        )
        // A congregation with no code yet keeps the ID parseable with a ?? placeholder.
        assertEquals("AD-??-ANT-207", externalTesterId(Division.ADULT, false, "", "ANT", 207))
    }

    @Test
    fun zipGradeNameSplitTakesTheLastWordAsLastName() {
        assertEquals("Mary Beth", zipGradeFirstName("Mary Beth Smith"))
        assertEquals("Smith", zipGradeLastName("Mary Beth Smith"))
        assertEquals("", zipGradeFirstName("Cher"))
        assertEquals("Cher", zipGradeLastName("Cher"))
    }
}
