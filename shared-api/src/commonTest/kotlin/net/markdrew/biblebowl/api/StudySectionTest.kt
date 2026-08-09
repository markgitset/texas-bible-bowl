package net.markdrew.biblebowl.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudySectionTest {

    @Test
    fun bySlugRoundTripsEveryEntry() {
        StudySection.entries.forEach { section ->
            assertEquals(section, StudySection.bySlug(section.slug))
        }
        assertNull(StudySection.bySlug("bogus"))
        assertNull(StudySection.bySlug(null))
        // Strict: enum constant names are not slugs.
        assertNull(StudySection.bySlug("PRACTICE_TESTS"))
    }

    @Test
    fun serializesAsTheSlug() {
        assertEquals("\"practice-tests\"", Json.encodeToString(StudySection.serializer(), StudySection.PRACTICE_TESTS))
        assertEquals(
            StudySection.DATA,
            Json.decodeFromString(StudySection.serializer(), "\"data-source-files\""),
        )
    }
}
