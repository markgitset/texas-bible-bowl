-- A question's permanent scope is a canonical scripture coordinate: book + book-relative chapter.
-- Before this, `chapter` alone implicitly meant "the current season's book", so a question could not be
-- reused when the 10-year study rotation repeats a set, and was ambiguous for multi-book sets. The study
-- set is NOT stored here — "questions in study set X" is a derived query over X's chapter ranges (see
-- QuestionRepository.list). book_code is the Book enum name; chapter stays nullable ("about the book as
-- a whole"), but a NULL book would re-introduce exactly the season-relative ambiguity being deleted, so
-- it becomes NOT NULL after the backfill.

ALTER TABLE questions ADD COLUMN book_code VARCHAR(3);

-- Backfill: derive the book from the stored refs. Two shapes occur in the wild — the printed form the
-- seeder writes ("Acts 1:1"; full name then a space) and the serialized form the column doc cites
-- ("ACT2:38"; enum code then a digit). The trailing space/digit anchors keep "Judges 5" from matching
-- Jude and "JUD1:5" from matching Judges; full-name matches beat code matches, then longer names win.
-- The canonical book list lives in core's Book.kt — this copy is acceptable for a one-time script.
WITH books(code, name) AS (VALUES
    ('GEN','Genesis'),('EXO','Exodus'),('LEV','Leviticus'),('NUM','Numbers'),('DEU','Deuteronomy'),
    ('JOS','Joshua'),('JDG','Judges'),('RUT','Ruth'),('SA1','1 Samuel'),('SA2','2 Samuel'),
    ('KI1','1 Kings'),('KI2','2 Kings'),('CH1','1 Chronicles'),('CH2','2 Chronicles'),('EZR','Ezra'),
    ('NEH','Nehemiah'),('EST','Esther'),('JOB','Job'),('PSA','Psalms'),('PRO','Proverbs'),
    ('ECC','Ecclesiastes'),('SOS','Song of Solomon'),('ISA','Isaiah'),('JER','Jeremiah'),
    ('LAM','Lamentations'),('EZE','Ezekiel'),('DAN','Daniel'),('HOS','Hosea'),('JOE','Joel'),
    ('AMO','Amos'),('OBA','Obadiah'),('JON','Jonah'),('MIC','Micah'),('NAH','Nahum'),
    ('HAB','Habakkuk'),('ZEP','Zephaniah'),('HAG','Haggai'),('ZEC','Zechariah'),('MAL','Malachi'),
    ('MAT','Matthew'),('MAR','Mark'),('LUK','Luke'),('JOH','John'),('ACT','Acts'),('ROM','Romans'),
    ('CO1','1 Corinthians'),('CO2','2 Corinthians'),('GAL','Galatians'),('EPH','Ephesians'),
    ('PHP','Philippians'),('COL','Colossians'),('TH1','1 Thessalonians'),('TH2','2 Thessalonians'),
    ('TI1','1 Timothy'),('TI2','2 Timothy'),('TIT','Titus'),('PHM','Philemon'),('HEB','Hebrews'),
    ('JAM','James'),('PE1','1 Peter'),('PE2','2 Peter'),('JO1','1 John'),('JO2','2 John'),
    ('JO3','3 John'),('JUD','Jude'),('REV','Revelation')
),
matched AS (
    SELECT q.id,
           b.code,
           row_number() OVER (
               PARTITION BY q.id
               ORDER BY (q.refs ILIKE b.name || ' %') DESC, length(b.name) DESC
           ) AS rn
      FROM questions q
      JOIN books b
        ON q.refs ILIKE b.name || ' %'
        OR q.refs ~ ('^' || b.code || '[0-9]')
)
UPDATE questions q SET book_code = m.code FROM matched m WHERE m.id = q.id AND m.rn = 1;

-- Rows with blank/unparseable refs: Acts is the only study set the question bank has ever been open
-- under (V1 shipped this season; the study-guide seeder has only ever run for Acts), so every pre-V5
-- row is Acts-scoped by construction.
UPDATE questions SET book_code = 'ACT' WHERE book_code IS NULL;

ALTER TABLE questions ALTER COLUMN book_code SET NOT NULL;

-- Chapters are book-relative and 1-based. A per-book upper bound isn't expressible here (it would need
-- all 66 chapter counts); the API validates against Book.chapterCount / StudySet.contains instead.
ALTER TABLE questions ADD CONSTRAINT questions_chapter_positive CHECK (chapter IS NULL OR chapter > 0);

-- Serves the derived study-set query: an OR of per-book (book_code, chapter BETWEEN) predicates.
CREATE INDEX questions_book_chapter_idx ON questions (book_code, chapter);
