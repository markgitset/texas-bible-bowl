package net.markdrew.biblebowl.api

/**
 * The "Make it in Kahoot" handoff on the Kahoot export card. Kahoot has no public API for creating
 * kahoots (its only official API is read-only reports for enterprise plans), so the most we can do
 * is save the import spreadsheet, open Kahoot's creator, and say exactly what to click. Both apps
 * show these same words.
 */
object KahootHandoff {
    /** Kahoot's creator: opens a new, blank kahoot in the user's own account (signing in first if needed). */
    const val CREATOR_URL: String = "https://create.kahoot.it/creator"

    /**
     * The import steps, naming [fileName] (the spreadsheet just saved) so it's easy to pick out of a
     * Downloads folder.
     */
    fun steps(fileName: String): List<String> = listOf(
        "In the Kahoot tab, sign in (a free account works).",
        "Click Add question, then Import spreadsheet.",
        "Choose $fileName from your downloads, then click Add questions.",
        "Give the kahoot a title and click Save — it's in your Kahoot library to host or assign.",
    )
}
