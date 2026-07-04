# CLAUDE.md — working notes for this repo

All-platform (web + Android + iOS-later) Texas Bible Bowl study/competition app in
Kotlin. See `README.md` for the module table and the plan file under `~/.claude/plans/`
for the roadmap. This file is the operational cheat-sheet: how to build, test, verify,
and deploy, plus the non-obvious gotchas.

## Modules (actual, as built)
`:core` `:shared-api` `:generation` `:app` `:server` — see `settings.gradle.kts`.
- **`:core`** is KMP but has a **`jvmMain` source set** that holds *server-only* JVM
  code (the whole Bible-text render engine + NLP/analysis). Common code (VerseRef,
  StudyData, etc.) is in `commonMain`; anything touching the copied bible-bowl JVM
  engine lives in `core/src/jvmMain`. (The plan said this would go in `:generation`;
  in practice it landed in `core/jvmMain`.)
- **`:generation`** holds the pure-Kotlin Typst *markup builders* used by both client
  and server (PracticeTest, Flashcards, QuizEngine).

## Reuse strategy (locked in by Mark)
**Copy bible-bowl JVM source into `core/jvmMain` when needed — do NOT depend on the
bible-bowl jar.** The earlier ports kept bible-bowl's exact package names
(`net.markdrew.biblebowl.*`, vendored `net.markdrew.chupacabra.*`), so an external jar
would collide. Copy code + curated data verbatim; port to `commonMain` only when a
client (web/app) actually needs to run it. The curated data (`core/src/jvmMain/resources/
word-lists/*.txt`, `acts/category-overrides.tsv`) is the app's own — safe to commit. ESV
*text* is copyrighted and stays out of git / server-side only.

## Build & test — task names differ per module
Gradle task names are **not** uniform across modules. Use these:
- Core (JVM): `./gradlew :core:jvmTest` (compile: `:core:compileKotlinJvm`)
- Server: `./gradlew :server:test` (compile: `:server:compileKotlin`)
- App: `:app:compileKotlinDesktop`, `:app:compileKotlinWasmJs`,
  `:app:compileDebugKotlinAndroid`, `:app:desktopTest`
  (there is **no** `:app:compileKotlinJvm` / `:app:build`-style single task).
- Full CI locally: `./gradlew :core:jvmTest :shared-api:jvmTest :generation:jvmTest
  :server:test :app:desktopTest` then `:app:assembleDebug` and
  `:app:wasmJsBrowserDistribution` (mirrors `.github/workflows/ci.yml`).

**Gotcha:** the Gradle daemon sometimes reports `compileKotlinJvm UP-TO-DATE` /
`BUILD SUCCESSFUL` after a source edit *without recompiling*. If a run finishes
instantly and everything is UP-TO-DATE right after you edited files, re-run with
`--rerun-tasks` to force a real compile before trusting a green result.

The wasmJs build prints many `ExperimentalWasmJsInterop` opt-in warnings from
`SavePdf.wasmJs.kt` — pre-existing and harmless, not from your change.

## Verifying generated PDFs locally (no ESV token needed)
Typst is installed at `/home/mark/bin/typst` (v0.14.2); the server shells out to it.
To eyeball a PDF feature without the ESV token: write a throwaway jvmTest that builds
`StudyData` from a small hardcoded `Passage` fixture (see `EsvIndexer(...).indexBook(
sequenceOf(passage))` in `BibleTextTypstTest`), dump the Typst string to the scratchpad,
`typst compile x.typ x.pdf`, then Read the PDF. Delete the throwaway test afterward.
This renders the real pipeline without hitting Crossway.

## Deploy — what's automatic vs manual
- **Web (GitHub Pages):** auto on push to `main` via `.github/workflows/pages.yml`.
  Live: https://markgitset.github.io/texas-bible-bowl/
- **CI (`ci.yml`):** runs tests + builds APK/web on push. **Does NOT deploy the backend.**
- **Backend (Fly.io):** **manual** — `fly deploy` (Mark's infra/secrets; you never see
  the ESV token or prod secrets, set via `fly secrets set`). Live:
  https://texas-bible-bowl.fly.dev . So a pushed backend change is NOT live until a
  manual deploy. Verify backend changes locally (unit test + local render), and only
  claim "live" after a deploy + hitting the endpoint.

## Conventions
- **No `Co-Authored-By: Claude` trailer** in commit messages (Mark's standing preference).
- Commit + push at each significant step (standing instruction).
- ESV license is a non-profit license: the ESV token, text cache, and all analysis
  caching stay **server-side only**.

## Text-PDF render pipeline (where the covered-text feature lives)
`core/jvmMain/.../generate/text/`: `AnnotatedDoc` (DisjointRangeMap annotation layers) →
`BibleTextWalker.walk(doc, studyData, options, handler)` → `TypstBibleTextWriter`'s
`bibleTextTypst(...)` returns a Typst string (server compiles it). Structural layers come
free from `studyData.toAnnotatedDoc(BOOK,CHAPTER,HEADING,VERSE,POETRY,PARAGRAPH,
LEADING_FOOTNOTE,FOOTNOTE)`. Feature layers are added on top:
- **REGEX** (highlighting) — from the category resolution (`AnnotationStore`/`WordList`/
  curated overrides), Postgres-cached in `text_annotations`. `Highlighting.kt` +
  `tbbHighlightPalette()`.
- **UNIQUE_WORD** (underline hapaxes) — from `oneTimeWords(studyData)` (pure
  `StudyData.wordIndex`, no NLP), gated on `TextOptions.underlineUniqueWords`.
- **Small-caps** — handled inline in `emitText` (`LORD` → `#smallcaps[Lord]`); no layer.

Endpoint: `GET /generate/bible-text.pdf?fontSize&twoColumns&justified&chapterBreaksPage&
highlight&underlineUniqueWords` (highlight on by default).
