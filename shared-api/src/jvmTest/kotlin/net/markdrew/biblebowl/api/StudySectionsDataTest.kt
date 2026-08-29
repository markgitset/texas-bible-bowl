package net.markdrew.biblebowl.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class StudySectionsDataTest {

    @Test
    fun `site data file matches the StudySection enum`() {
        // jvmTest runs with the module directory as its working directory
        val dataFile = File("../site/data/study-sections.json")
        assertEquals(
            studySectionsJson(),
            dataFile.readText(),
            "site/data/study-sections.json is stale relative to the StudySection enum — " +
                "regenerate it with ./gradlew :shared-api:generateStudySectionsData and commit it.",
        )
    }
}
