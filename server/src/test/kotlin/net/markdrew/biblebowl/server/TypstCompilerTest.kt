package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.generate.studyguide.tbbLogoBytes
import net.markdrew.biblebowl.server.typst.TypstCompiler
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypstCompilerTest {

    @Test
    fun stagedAssetsAreReferenceableFromTheSource() {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping asset-staging test")
            return
        }
        val logo = tbbLogoBytes()
        assertNotNull(logo, "the bundled TBB logo should be on the classpath")

        // Without staging, `#image("logo.png")` would fail to resolve; staging it makes the compile succeed.
        val pdf = TypstCompiler.compile(
            """#image("logo.png", width: 1in)""",
            layoutRevision = 1,
            assets = mapOf("logo.png" to logo),
        )
        assertTrue(pdf.size > 4 && pdf.decodeToString(0, 4) == "%PDF", "should compile to a PDF")
    }

    @Test
    fun aFloodOfWarningsDoesNotDeadlockTheCompile() {
        if (!TypstCompiler.isAvailable) {
            println("typst not on PATH; skipping warning-flood test")
            return
        }
        // An unknown font emits a warning per text run; over enough runs this once filled the stdout
        // pipe and deadlocked the compile until the timeout (regression guard for the concurrent drain).
        val body = (1..4000).joinToString("\n\n") { "paragraph $it of the warning flood" }
        val pdf = TypstCompiler.compile("#set text(font: \"No Such Font ZZZ\")\n$body", layoutRevision = 1, timeoutSeconds = 30)
        assertTrue(pdf.size > 4 && pdf.decodeToString(0, 4) == "%PDF", "should still compile despite the warnings")
    }
}
