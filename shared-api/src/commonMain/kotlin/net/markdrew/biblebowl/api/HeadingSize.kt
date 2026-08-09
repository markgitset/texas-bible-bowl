package net.markdrew.biblebowl.api

/**
 * How large a study-text heading renders, as a multiple of the body text size.
 *
 * One shared vocabulary for both heading controls (chapter and section), so the two are chosen
 * independently from the same chips and *either* can be the larger one. That is deliberate: a
 * section heading bigger than the chapter heading — which is what the study text shipped with
 * before this option existed — is a real preference some readers prefer, not a bug to erase.
 * The defaults simply no longer impose it.
 *
 * Sizes are multipliers rather than point sizes because the body size is user-selectable (6–24pt
 * server-side, 9–15pt in the UI chips): an absolute heading size is right at exactly one body size
 * and wrong everywhere else. The old fixed 14pt/16pt rendered *smaller* than the text at the top
 * of the range and dwarfed it at the bottom.
 *
 * [DEFAULT_CHAPTER] and [DEFAULT_SECTION] are Typst's own heading defaults (1.4em / 1.2em), which
 * are correctly ordered and track the body size at every size.
 */
enum class HeadingSize(val label: String, val slug: String, val scale: Double) {
    SAME_AS_TEXT("Same as text", "same", 1.0),
    SMALL("Small", "small", 1.2),
    MEDIUM("Medium", "medium", 1.4),
    LARGE("Large", "large", 1.7);

    companion object {
        /** Chapter headings default to Typst's level-1 size (1.4em). */
        val DEFAULT_CHAPTER: HeadingSize = MEDIUM

        /** Section headings default to Typst's level-2 size (1.2em) — one step below the chapter. */
        val DEFAULT_SECTION: HeadingSize = SMALL

        /** Strict slug lookup, for query parameters; null when unrecognized so callers can fall back. */
        fun bySlug(slug: String?): HeadingSize? = entries.firstOrNull { it.slug == slug }
    }
}
