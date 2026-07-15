package net.markdrew.biblebowl.generate.text

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Format-agnostic layout/structural options and content features for Bible-text rendering.
 *
 * @param dateLine date text stamped in the page footer next to "Texas Bible Bowl" — the season's
 *   event dates in production (e.g. "April 2–4, 2027"); defaults to today's date
 * @param fontSize body text size in points
 * @param twoColumns if true, render in two columns
 * @param chapterBreaksPage if true, force a page break between chapters
 * @param useHeadingsForChapters if true, render chapter titles as heading-style paragraphs instead of
 *   inline labels at the start of the first verse
 * @param chapterEndLines center the chapter label and draw bold, black horizontal lines extending across the column
 * @param mainFont main body text font family
 * @param headingFont chapter and section headings font family
 * @param verseNumFont verse numbers font family
 * @param chapterFontSize chapter heading font size in points
 * @param headingFontSize section heading font size in points
 * @param footnoteFontSize footnote font size in points
 * @param justified body text justification
 * @param underlineUniqueWords if true, underline words that occur exactly once in the study set
 * @param customHighlights palette of regex/category-driven highlights, with caller-chosen colors.
 * @param smallCaps map from match text to its small-caps replacement (e.g., `"LORD" -> "Lord"`)
 * @param verseOnNewLine if true, start every verse on its own line. Affects prose only.
 */
data class TextOptions(
    val dateLine: String = LocalDate.now().format(DateTimeFormatter.ofPattern("LLLL d, uuuu")),
    val fontSize: Int = 12,
    val twoColumns: Boolean = false,
    val chapterBreaksPage: Boolean = false,
    val useHeadingsForChapters: Boolean = false,
    val chapterEndLines: Boolean = false,
    val mainFont: String = "Quattrocento Sans",
    val verseNumFont: String = "Quattrocento Sans",
    val headingFont: String = "Quattrocento Sans",
    val chapterFontSize: Int = 14,
    val headingFontSize: Int = 16,
    val footnoteFontSize: Int = 10,
    val justified: Boolean = false,
    val underlineUniqueWords: Boolean = false,
    val customHighlights: HighlightPalette = HighlightPalette.empty(),
    // Small-caps replacements (e.g. "LORD" -> "Lord") come from the WordList dataset, which is a later copy;
    // default to none for now. Step 2 (highlighting) brings the curated map in.
    val smallCaps: Map<String, String> = emptyMap(),
    val verseOnNewLine: Boolean = false,
)
