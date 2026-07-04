package net.markdrew.biblebowl.analysis

import net.markdrew.biblebowl.model.Excerpt
import net.markdrew.biblebowl.model.StudyData
import org.intellij.lang.annotations.Language

/**
 * Number detection ported verbatim from bible-bowl: a single combined regex that catches numerals,
 * spelled-out cardinals/ordinals, and fractions. Deterministic and self-contained (no external data), so it
 * runs anywhere the study text is available.
 */

/** Numerals like "1", "12", or "1,234" (with comma grouping). */
@Language("RegExp") const val NUMERAL_PATTERN = """\d{1,3}(?:,\d\d\d)*"""

/** Base fraction word stems: "half", "third", "fourth", …, with optional plural "s". */
@Language("RegExp") const val BASE_FRACTIONS =
    """(?:hal(?:f|ve)|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth)s?"""

/** Contexts in which a bare "one" should be treated as the number rather than the article/pronoun. */
@Language("RegExp") const val ONE_AS_NUMBER =
    """(?:(?<=-|just |in )one|(?<!(the|no) )\bone(?= and|[\- ]$BASE_FRACTIONS| coin| sinner| mile| hour| staff| (drawn )?out of| of every| have chased| eye| or two| flesh| talent| for)|(?<=, )one(?=[;:]))"""

/** Spelled-out numbers that don't fit the regular "teens/-ty" pattern (e.g. "zero", "couple", "myriad"). */
@Language("RegExp") const val SPECIAL_NUMBERS_PATTERN =
    """\b(?:zero|$ONE_AS_NUMBER|a couple|twos?|threes?|fives?|tens?|elevens?|twelves?|fort(y|ies)|hundreds?|thousands?|myriads?)"""

/** Single spelled-out cardinal numbers, optionally suffixed with "fold" (e.g. "sevenfold", "hundredfold"). */
@Language("RegExp") const val NUMBER_PATTERN =
    """\b(?:(?:$SPECIAL_NUMBERS_PATTERN|(?:twen|thir|fours?|fif|six(es)?|sevens?|eigh(ts?)?|nines?)(?:teens?|ty|ties)?)(?:fold)?)\b"""

/** Compound spelled-out numbers like "six hundred", "ten thousand", "sixty-seven thousand". */
@Language("RegExp") const val MULTI_NUMBER_PATTERN = """$NUMBER_PATTERN(?:(?:-| | of |)$NUMBER_PATTERN)*"""

/** Multiples of ten: "twenty", "thirty", …, "ninety". */
@Language("RegExp") const val TENS = """(?:(?:twen|thir|for|fif|six|seven|eigh|nine)ty)"""

/** Ordinal multiples of ten: "twentieth", "thirtieth", …, "ninetieth". */
@Language("RegExp") const val TENS_ORDINALS = """(?:(?:twen|thir|for|fif|six|seven|eigh|nine)tieth)"""

/** Teen cardinals: "thirteen", "fourteen", …, "nineteen". */
@Language("RegExp") const val TEENS = """(?:(?:thir|four|fif|six|seven|eigh|nine)teen)"""

/** Contexts in which "first" should be treated as an ordinal rather than the adjective ("the first"). */
@Language("RegExp") const val FIRST_AS_ORDINAL = """(?:first(?=\s+(?:day|month))|(?<=(to them|go up|(?<!\bat )[Tt]he|who) )first\b)"""

/** All ordinal forms: "first", "second", …, "twentieth", "twenty-first", etc. */
@Language("RegExp") const val ORDINALS =
    """(?:$FIRST_AS_ORDINAL|(?<!at the )(?:$MULTI_NUMBER_PATTERN and )?(?:$TENS_ORDINALS|(?:$TENS-first)\b|""" +
        """(?:$TENS-)?(?:(?<=and )first|second|third|(?:four|fif|six|seven|eigh|nin|ten|eleven|twelf|$TEENS)th))\b)"""

/** Fractions like "half", "one-third", "two and a half", "three and seven-eighths". */
@Language("RegExp") const val FRACTIONS =
    """(?:\b(?:$MULTI_NUMBER_PATTERN(?:\s+and\s+(a|$MULTI_NUMBER_PATTERN))?[\-\s]\s*)?$BASE_FRACTIONS\b)"""

/** Union of [FRACTIONS], [ORDINALS], [MULTI_NUMBER_PATTERN], and [NUMERAL_PATTERN]. */
@Language("RegExp") const val COMBINED_NUMBER_PATTERN = "$FRACTIONS|$ORDINALS|$MULTI_NUMBER_PATTERN|$NUMERAL_PATTERN"

/** Compiled, case-insensitive form of [COMBINED_NUMBER_PATTERN]. */
val NUMBER_REGEX = COMBINED_NUMBER_PATTERN.toRegex(RegexOption.IGNORE_CASE)

/** Returns every number occurrence in [text] (numerals, cardinals, ordinals, and fractions) as an [Excerpt]. */
fun findNumbers(text: String): Sequence<Excerpt> =
    NUMBER_REGEX.findAll(text).map { Excerpt(it.value, it.range) }

/** Builds a number index from precomputed number [ranges], grouping by lowercased text with the verses each appears in. */
fun buildNumbersIndex(studyData: StudyData, ranges: List<IntRange>): List<WordIndexEntry> =
    ranges.map { studyData.excerpt(it) }
        .groupBy { it.excerptText.lowercase() }
        .map { (key, excerpts) ->
            WordIndexEntry(key, excerpts.map { studyData.verseEnclosing(it.excerptRange) ?: error("No verse for $it") })
        }

/**
 * Builds the number index for [studyData] as count-carrying entries (each number → the verses it occurs in,
 * with per-verse occurrence counts), excluding [stopWords]. Sorted alphabetically by the number's text.
 */
fun numbersIndex(studyData: StudyData, stopWords: Set<String> = STOP_WORDS): List<WordIndexEntryC> =
    buildNumbersIndex(studyData, findNumbers(studyData.text).map { it.excerptRange }.toList())
        .map { entry ->
            WordIndexEntryC(
                entry.key,
                entry.values.groupingBy { it }.eachCount().map { (verseRef, count) -> WithCount(verseRef, count) },
            )
        }
        .filterNot { it.key in stopWords }
        .sortedBy { it.key.lowercase() }
