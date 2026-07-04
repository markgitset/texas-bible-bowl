package net.markdrew.biblebowl.generate.text

import net.markdrew.biblebowl.analysis.AnnotationStore
import net.markdrew.biblebowl.generate.text.typst.bibleTextTypst
import net.markdrew.biblebowl.model.AnalysisUnit
import net.markdrew.biblebowl.model.AnalysisUnit.BOOK
import net.markdrew.biblebowl.model.AnalysisUnit.CHAPTER
import net.markdrew.biblebowl.model.AnalysisUnit.FOOTNOTE
import net.markdrew.biblebowl.model.AnalysisUnit.HEADING
import net.markdrew.biblebowl.model.AnalysisUnit.LEADING_FOOTNOTE
import net.markdrew.biblebowl.model.AnalysisUnit.PARAGRAPH
import net.markdrew.biblebowl.model.AnalysisUnit.POETRY
import net.markdrew.biblebowl.model.AnalysisUnit.REGEX
import net.markdrew.biblebowl.model.AnalysisUnit.VERSE
import net.markdrew.biblebowl.model.StudyData
import net.markdrew.chupacabra.core.DisjointRangeMap

/**
 * Texas Bible Bowl highlight palette: category id → color, matching the historically-shipping DOCX colors.
 * Detection is done by the category resolution (word lists + curated overrides), so the regex sets here are
 * empty — the palette only supplies each category's color to the writer.
 */
fun tbbHighlightPalette(): HighlightPalette = HighlightPalette(
    listOf(
        HighlightColor("men", Triple(0x99, 0xcc, 0xff)) to emptySet(),
        HighlightColor("places", Triple(0x99, 0xff, 0x99)) to emptySet(),
        HighlightColor("women", Triple(0xff, 0x99, 0xff)) to emptySet(),
        HighlightColor("people-groups", Triple(0xcc, 0xcc, 0xcc)) to emptySet(),
        HighlightColor("divine", Triple(0xff, 0xff, 0x00)) to emptySet(),
        HighlightColor("numbers", Triple(0xff, 0xb6, 0x6c)) to emptySet(),
        HighlightColor("other", Triple(0x2e, 0xe6, 0xd9)) to emptySet(),
    ),
)

/**
 * Builds the render doc with the structural layers plus a REGEX highlight layer taken from the study set's
 * category resolution ([AnnotationStore.categoryResolution]), keeping only categories present in [palette].
 */
fun highlightedBibleTextDoc(
    studyData: StudyData,
    store: AnnotationStore,
    palette: HighlightPalette = tbbHighlightPalette(),
): AnnotatedDoc<AnalysisUnit> {
    val paletteCategories: Set<String> = palette.rules.map { it.first }.toSet()
    val regexRanges = DisjointRangeMap<String>().apply {
        store.categoryResolution(studyData.studySet).forEach { (range, category) ->
            if (category in paletteCategories) put(range, category)
        }
    }
    return studyData.toAnnotatedDoc(BOOK, CHAPTER, HEADING, VERSE, POETRY, PARAGRAPH, LEADING_FOOTNOTE, FOOTNOTE, REGEX)
        .apply { setAnnotations(REGEX, regexRanges) }
}

/** Renders the covered text with categorized name/number highlighting (the key feature of the text download). */
fun highlightedBibleTextTypst(
    studyData: StudyData,
    store: AnnotationStore,
    options: TextOptions = TextOptions(),
): String {
    val palette = tbbHighlightPalette()
    return bibleTextTypst(studyData, options.copy(customHighlights = palette), highlightedBibleTextDoc(studyData, store, palette))
}
