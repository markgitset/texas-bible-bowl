package net.markdrew.biblebowl.analysis

/** An item paired with how many times it occurs */
data class WithCount<T>(val item: T, val count: Int)
