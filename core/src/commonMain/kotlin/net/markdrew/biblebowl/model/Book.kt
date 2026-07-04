package net.markdrew.biblebowl.model

/**
 * The 66 books of the Protestant Bible canon, in canonical order
 *
 * Each book carries display names at three levels (full, brief, three-letter) and a positional [number] (1..66)
 * used to encode/decode packed [AbsoluteChapterNum] and [AbsoluteVerseNum] values.
 *
 * @param fullName the unabbreviated book name (e.g. "1 Corinthians")
 * @param chapterCount the number of chapters in this book (Protestant canon)
 * @param briefName a short name for index/footer use; defaults to [fullName]
 * @param twoLetterCode optional override for the two-letter code; defaults to the first two letters of [name]
 */
enum class Book(
    val fullName: String,
    val chapterCount: Int,
    val briefName: String = fullName,
    private val twoLetterCode: String? = null,
) {
    GEN("Genesis", 50, "Gen"),
    EXO("Exodus", 40, "Exo"),
    LEV("Leviticus", 27, "Lev"),
    NUM("Numbers", 36, "Num"),
    DEU("Deuteronomy", 34, "Deut", "DT"),
    JOS("Joshua", 24, "Jos"),
    JDG("Judges", 21, "Jdg", twoLetterCode = "JG"),
    RUT("Ruth", 4, "Ru"),
    SA1("1 Samuel", 31, "1 Sam"),
    SA2("2 Samuel", 24, "2 Sam"),
    KI1("1 Kings", 22),
    KI2("2 Kings", 25),
    CH1("1 Chronicles", 29, "1 Chron"),
    CH2("2 Chronicles", 36, "2 Chron"),
    EZR("Ezra", 10),
    NEH("Nehemiah", 13, "Neh"),
    EST("Esther", 10),
    JOB("Job", 42),
    PSA("Psalms", 150),
    PRO("Proverbs", 31, "Prov"),
    ECC("Ecclesiastes", 12, "Eccl"),
    SOS("Song of Solomon", 8, "Song"),
    ISA("Isaiah", 66),
    JER("Jeremiah", 52, "Jer"),
    LAM("Lamentations", 5, "Lam"),
    EZE("Ezekiel", 48, "Eze"),
    DAN("Daniel", 12),
    HOS("Hosea", 14),
    JOE("Joel", 3),
    AMO("Amos", 9),
    OBA("Obadiah", 1, "Oba"),
    JON("Jonah", 4),
    MIC("Micah", 7),
    NAH("Nahum", 3),
    HAB("Habakkuk", 3, "Hab"),
    ZEP("Zephaniah", 3, "Zeph"),
    HAG("Haggai", 2),
    ZEC("Zechariah", 14, "Zech"),
    MAL("Malachi", 4, "Mal"),
    MAT("Matthew", 28, "Matt"),
    MAR("Mark", 16),
    LUK("Luke", 24),
    JOH("John", 21),
    ACT("Acts", 28),
    ROM("Romans", 16),
    CO1("1 Corinthians", 16, "1 Cor"),
    CO2("2 Corinthians", 13, "2 Cor"),
    GAL("Galatians", 6, "Gal"),
    EPH("Ephesians", 6, "Eph"),
    PHP("Philippians", 4, "Phil"),
    COL("Colossians", 4, "Col"),
    TH1("1 Thessalonians", 5, "1 Thess"),
    TH2("2 Thessalonians", 3, "2 Thess"),
    TI1("1 Timothy", 6, "1 Tim"),
    TI2("2 Timothy", 4, "2 Tim"),
    TIT("Titus", 3),
    PHM("Philemon", 1, "Phm"),
    HEB("Hebrews", 13, "Heb"),
    JAM("James", 5),
    PE1("1 Peter", 5, "1 Pe"),
    PE2("2 Peter", 3, "2 Pe"),
    JO1("1 John", 5, "1 Jo"),
    JO2("2 John", 1, "2 Jo"),
    JO3("3 John", 1, "3 Jo"),
    JUD("Jude", 1),
    REV("Revelation", 22, "Rev");

    /** 1-based canonical position (Genesis = 1, Revelation = 66) */
    val number = ordinal + 1

    /** Largest possible chapter ref for this book, useful as a sentinel "to end of book" upper bound */
    val lastChapterRef = ChapterRef(this, chapterCount)

    /** Two-letter uppercase code for this book (e.g. "GE", "JG", "DT") */
    val twoLetter = (twoLetterCode ?: name.take(2)).uppercase()

    /** Returns the [VerseRef] at [chapter]:[verse] in this book. */
    fun verseRef(chapter: Int, verse: Int): VerseRef = chapterRef(chapter).verse(verse)

    /** Returns the [ChapterRef] for [chapter] in this book. */
    fun chapterRef(chapter: Int): ChapterRef = ChapterRef(this, chapter)

    /** Returns the inclusive [ChapterRange] from chapter [first] through [last] of this book. */
    fun chapterRange(first: Int, last: Int): ChapterRange = chapterRef(first)..chapterRef(last)

    /** Returns a sentinel [ChapterRange] covering every chapter of this book; the upper bound is [lastChapterRef]. */
    fun allChapters(): ChapterRange = chapterRef(1)..lastChapterRef

    companion object {
        val DEFAULT = MAT

        /** Returns the book at 1-based canonical position [n] (1 = Genesis, 66 = Revelation). */
        fun fromNumber(n: Int): Book = entries[n - 1]

        /**
         * Lenient parser tolerating both the three-letter enum name (case-insensitive) and a prefix of [fullName].
         *
         * Returns [default] if [s] is null. If [s] is non-null and matches neither form, returns [default] as
         * well (i.e. unknown input falls back rather than throwing).
         */
        fun parse(s: String?, default: Book? = DEFAULT): Book? = if (s == null) default else try {
            valueOf(s.uppercase())
        } catch (e: IllegalArgumentException) {
            entries.firstOrNull { it.fullName.lowercase().startsWith(s.lowercase()) } ?: default
        }
    }
}
