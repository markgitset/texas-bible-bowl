package net.markdrew.biblebowl.generation.typst

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NametagsTest {

    @Test
    fun emitsOneBadgeEntryPerTagGroupedIntoSheets() {
        val source = nametagsTypst(
            listOf(
                NametagSheet(
                    heading = "Texas Bible Bowl 2027 — Bandina",
                    tags = listOf(
                        Nametag("Amy Kid", "First Church", role = "Junior", testerId = 1),
                        Nametag("Helpful Aunt", "First Church", role = "Volunteer"),
                    ),
                ),
                NametagSheet(
                    heading = "Texas Bible Bowl 2027 — White River",
                    tags = listOf(Nametag("Cal Kid", "Memorial", role = "Senior", testerId = 2)),
                ),
            )
        )
        assertContains(source, """(heading: "Texas Bible Bowl 2027 — Bandina", tags: (""")
        assertContains(source, """(name: "Amy Kid", congregation: "First Church", role: "Junior", tester: "1"),""")
        // A guest has no tester id — the empty string suppresses the badge's corner number.
        assertContains(source, """(name: "Helpful Aunt", congregation: "First Church", role: "Volunteer", tester: ""),""")
        assertContains(source, """(heading: "Texas Bible Bowl 2027 — White River", tags: (""")
        assertTrue(
            source.indexOf("Cal Kid") > source.indexOf("White River"),
            "tags stay inside their sheet",
        )
    }

    @Test
    fun escapesTypstStringDelimitersInNames() {
        val source = nametagsTypst(
            listOf(NametagSheet("Event", listOf(Nametag("""Quote "Q" O'\Brien""", "St. \"B\" Church"))))
        )
        assertContains(source, """name: "Quote \"Q\" O'\\Brien"""")
        assertContains(source, """congregation: "St. \"B\" Church"""")
        assertFalse(source.contains("""name: "Quote "Q""""), "raw quotes must not leak into markup")
    }
}
