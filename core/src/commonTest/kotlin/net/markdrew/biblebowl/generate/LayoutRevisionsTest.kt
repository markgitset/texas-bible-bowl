package net.markdrew.biblebowl.generate

import kotlin.test.Test
import kotlin.test.assertTrue

class LayoutRevisionsTest {

    /**
     * The corner mark rides a foreground set-rule prepended to the document plus an invisible
     * end-of-document marker appended after it — the rule must come first (set rules only affect
     * what follows), the marker last (it is how the rule finds the final physical page), and the
     * source must sit between them untouched.
     */
    @Test
    fun stampWrapsTheSourceWithForegroundRuleAndEndMarker() {
        val source = "#set page(paper: \"us-letter\")\nHello, world"
        val stamped = stampLayoutRevision(source, 7)
        assertTrue(stamped.startsWith("#set page(foreground:"), "the foreground rule must precede the document")
        assertTrue("[r7]" in stamped, "the mark must carry the revision number")
        assertTrue(source in stamped, "the document source must be embedded unmodified")
        assertTrue(
            stamped.substringAfter(source).contains("#metadata"),
            "the end-of-document marker must follow the source",
        )
    }
}
