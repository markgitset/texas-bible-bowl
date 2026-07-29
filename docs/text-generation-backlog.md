# Study-text generation backlog — more layout options

*Working backlog for the printable study-text feature (the `Printable study text` card in the
Study & Practice "The Text" section). Source of truth for what to add to the text-PDF options
next; refine and build one at a time. Check items off as they land.*

**Where the feature lives** (see AGENTS.md "Text-PDF render pipeline"):
`core/jvmMain/.../generate/text/` — `TypstBibleTextWriter.bibleTextTypst(...)` builds the Typst
string from a `TextOptions`; the server compiles it. Endpoint
`GET /generate/bible-text.pdf?fontSize&twoColumns&justified&chapterBreaksPage&
useHeadingsForChapters&chapterEndLines&verseOnNewLine&highlight&underlineUniqueWords`. The
option UI is duplicated in both clients — web `web/.../screens/DownloadsScreen.kt`
(`StudyTextChoices` + `StudyTextOptions`/`studyTextUrl`) and Compose
`app/.../screens/StudyScreens.kt` (`StudyTextChoices` + `StudyTextOptions` +
`PdfFileNames.bibleText`). A new option touches: `TextOptions`, the Typst writer, the endpoint
query params, `PdfFileNames.bibleText` (cache-key/filename), and both clients' choice
state + option controls.

## Wanted options

- [ ] **Footnotes always smaller than the body text.** Footnote text should render at a size
  strictly smaller than the current body `fontSize`, at every font size — never equal to or
  larger than the text. Today's footnote sizing should be audited against each selectable body
  size (9–15 pt) so the relationship holds throughout. This is a correctness/typographic
  guarantee, not a user toggle.

- [ ] **User-choosable heading sizes, relative to the body text.** Let the user pick how large
  the **chapter headings** and the **subject (section) headings** render, expressed *relative*
  to the body text size (e.g. a scale/step, so it tracks the chosen `fontSize` rather than being
  an absolute point size). Chapter-heading size and subject-heading size should be independently
  choosable. Surface as new controls in both clients' study-text Customize surface, thread through
  `TextOptions` → the Typst writer, and fold into the PDF cache key / filename.
