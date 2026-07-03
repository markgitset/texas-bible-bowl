package net.markdrew.biblebowl.generate.indices

import net.markdrew.biblebowl.analysis.WithCount

/** Wraps a formatter so its output uses non-breaking spaces (keeps references on one line). */
fun <T> ((T) -> String).noBreak(): (T) -> String = { ref -> this(ref).replace(' ', '\u00a0') }

/** Lifts a formatter of [T] to one of [WithCount]<[T]>, appending the count in parentheses when > 1. */
fun <T> ((T) -> String).withCount(): (WithCount<T>) -> String =
    { (item, count) -> this(item) + if (count > 1) " ($count)" else "" }

/** Appends a "(×N)" multiplicity marker to [s] when [count] > 1. */
fun formatWithCount(s: String, count: Int): String =
    s + (if (count > 1) " (×$count)" else "")
