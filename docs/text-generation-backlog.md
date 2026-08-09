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

- [x] **Footnotes always smaller than the body text.** Footnote text should render at a size
  strictly smaller than the current body `fontSize`, at every font size — never equal to or
  larger than the text. Today's footnote sizing should be audited against each selectable body
  size (9–15 pt) so the relationship holds throughout. This is a correctness/typographic
  guarantee, not a user toggle.
  - **As built:** by **deleting** the `#show footnote.entry: set text(size: …)` rule and
    `TextOptions.footnoteFontSize` entirely. Typst already renders footnote entries at `0.85em`
    (measured pixel-exact at 6/9/15/24pt) — body-relative, so always smaller than the text. The
    fixed `10pt` we inherited from the LaTeX-era engine copy (4a085bf) was *overriding* that good
    default, which is the whole bug: it was right only at 11pt and rendered footnotes **larger**
    than the text at 6–9pt. A test asserts we emit no size override across the server's coerced
    6–24pt range, so nobody re-adds one.
  - **Three findings worth keeping:** (1) Don't set a footnote size at all — but if you ever must,
    it can't be a relative `em`: it compounds with Typst's `0.85em` rather than replacing it
    (`0.87em` renders at ~`0.74em`). (2) Ratio is now Typst's 85%, not a number of ours; prod and
    local both pin `TYPST_VERSION=v0.14.2` (server/Dockerfile), so it can't drift without a
    reviewed bump. (3) The PDF cache is keyed on filename + content stamp, and footnote size isn't
    in the filename, so cached PDFs would have kept the old layout — hence
    `BIBLE_TEXT_LAYOUT_REVISION` folded into the bible-text stamp salt. **Bump it for any future
    change here** (the next item qualifies).

- [x] **User-choosable heading sizes, relative to the body text.** Let the user pick how large
  the **chapter headings** and the **subject (section) headings** render, expressed *relative*
  to the body text size (e.g. a scale/step, so it tracks the chosen `fontSize` rather than being
  an absolute point size). Chapter-heading size and subject-heading size should be independently
  choosable. Surface as new controls in both clients' study-text Customize surface, thread through
  `TextOptions` → the Typst writer, and fold into the PDF cache key / filename.

  **Groundwork measured while fixing the footnote item (2026-08-08) — this item also fixes a live
  bug, not just adds a knob.** `chapterFontSize = 14` and `headingFontSize = 16` are absolute
  points emitted inside `heading(level: 1)` / `heading(level: 2)`, so like the old footnote size
  they don't track `fontSize`. Two defects follow:
  - **Proportionality.** At 6pt body the headings run 2.3–2.7× the text and dominate the page; at
    24pt they are *smaller* than the scripture text. This bites inside the shipped UI, not just at
    hand-crafted sizes: at **15pt — the largest chip offered** — the chapter heading (14pt) is
    already smaller than the body and the section heading (16pt) is nearly the same size.
  - **Inverted hierarchy.** The section heading (`level: 2`, 16pt) is *larger* than the chapter
    heading (`level: 1`, 14pt) at every size. Decide deliberately whether that's intended before
    picking defaults — 14/16 looks like a deliberate pair, but it contradicts the heading levels.

  **Typst's unaided defaults are `1.4em` (L1) and `1.2em` (L2)** — measured exact at 6pt (8.4/7.2),
  10pt (14/12), and 20pt (28/24). They're body-relative and correctly ordered, so they're a sound
  starting point for the defaults; taking them as-is would make the chapter heading bigger and the
  section heading smaller than today at the default 11pt body. **This is a visible change to the
  flagship PDF at the common size** — which is why it was deliberately left to this item rather
  than slipped into the footnote fix (Mark, 2026-08-08): choosing the defaults *is* this item's
  design decision, and doing it twice would re-proportion the PDF twice.

  Blast radius differs between the two: section headings render in every study-text PDF, chapter
  headings only when `useHeadingsForChapters` is on. Remember to bump `BIBLE_TEXT_LAYOUT_REVISION`.

  *Not a bug elsewhere:* `PracticeTest`, `Awards`, `Flashcards`, and `Nametags` also use absolute
  sizes, but each has a fixed 11pt body, so they're internally consistent. Study text is the only
  generator with a user-selectable body size, which is what makes absolute sizing wrong in it.

  - **As built (2026-08-08):** `HeadingSize` in `:shared-api` — one shared chip vocabulary
    (`Same as text` 1.0 / `Small` 1.2 / `Medium` 1.4 / `Large` 1.7) used by *both* controls, so
    they're picked independently from the same list. `TextOptions` carries
    `chapterHeadingScale`/`sectionHeadingScale` (replacing `chapterFontSize`/`headingFontSize`);
    the writer multiplies by `fontSize` and emits **absolute points**. Defaults are Medium/Small
    = Typst's own 1.4/1.2. Query params `chapterHeadingSize`/`sectionHeadingSize` take slugs and
    fall back to the defaults when unrecognized (a stale link still renders); non-default choices
    are spelled into the filename (`-ch-head-small-sec-head-large`) and
    `BIBLE_TEXT_LAYOUT_REVISION` went to 2.
  - **The inverted hierarchy was deliberate, not a bug** (Mark, 2026-08-08): a section heading
    larger than the chapter heading is a real preference some readers hold — which is a large part
    of *why* these are user-selectable. So it stays reachable (pick Small chapter + Medium section
    to get the pre-2026-08 look); it just isn't the default any more. Don't "fix" it away.
  - **Scales must not be emitted as `em`.** Same trap as the footnote item, one level down: these
    sizes are set *inside* `heading(...)`, where Typst has already applied its 1.4em/1.2em, so
    `1.4em` would compound to 1.96em. Multiplying in Kotlin and emitting points tracks the body
    size without compounding.
  - **Typst PDFs are not byte-reproducible across time** — they embed `/CreationDate` and
    `/ModDate` (identical back-to-back, differing seconds apart). Any test that compares rendered
    PDF bytes to prove an option took effect passes trivially; assert on the `Content-Disposition`
    filename (also the cache key) or on the Typst source instead.
