package net.markdrew.biblebowl.generation.typst

/**
 * Escapes the Typst structural delimiters: `\`, `[`, `]`, `#`, `*`, `_`.
 *
 * Ported verbatim from bible-bowl's typst/Indices.kt — backslash first so escapes aren't double-escaped.
 */
fun escapeTypst(s: String): String =
    s.replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("#", "\\#")
        .replace("*", "\\*")
        .replace("_", "\\_")
