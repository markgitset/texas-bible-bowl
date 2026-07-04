package net.markdrew.biblebowl.server

import net.markdrew.biblebowl.server.study.PostgresAnnotationCache
import net.markdrew.chupacabra.core.DisjointRangeMap
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationCacheTest {

    @Test
    fun resolutionSerializesAndDeserializesLosslessly() {
        val original = DisjointRangeMap(0..4 to "men", 10..15 to "places", 20..22 to "numbers")
        val roundTripped = PostgresAnnotationCache.deserialize(PostgresAnnotationCache.serialize(original))
        assertEquals(
            original.entries.map { it.key to it.value },
            roundTripped.entries.map { it.key to it.value },
        )
    }

    @Test
    fun emptyResolutionRoundTrips() {
        val empty = DisjointRangeMap<String>()
        assertEquals(0, PostgresAnnotationCache.deserialize(PostgresAnnotationCache.serialize(empty)).size)
    }
}
