package net.markdrew.biblebowl.api

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

class StudySectionsDataTest {

    @Test
    fun `site data file matches the StudySection enum`() {
        // jvmTest runs with the module directory as its working directory
        val dataFile = File("../site/data/study-sections.json")
        val expected = studySectionsJson()
        if (dataFile.readText() != expected) {
            // Self-heal: builds regenerate this file anyway (see generateStudySectionsData),
            // so write the fix here too and make the failure purely "commit what's on disk".
            // In CI the checkout is ephemeral — the failure is what matters there.
            dataFile.writeText(expected)
            fail(
                "site/data/study-sections.json was stale relative to the StudySection enum. " +
                    "It has been regenerated — review and commit it.",
            )
        }
    }
}
