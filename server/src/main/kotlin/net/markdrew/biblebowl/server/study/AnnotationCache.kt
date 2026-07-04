package net.markdrew.biblebowl.server.study

import net.markdrew.biblebowl.server.data.TextAnnotationsTable
import net.markdrew.chupacabra.core.DisjointRangeMap
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Persists computed text-analysis layers (range → value maps) so the expensive resolution runs at most once
 * per (study set, text, definition) — the DB equivalent of bible-bowl's on-disk annotation cache. Survives
 * scale-to-zero restarts (Fly disk is ephemeral; Postgres is not).
 */
interface AnnotationCache {
    /** Returns the cached resolution, or null if absent or stale (text/definition changed). */
    fun get(studySet: String, sourceKey: String, textHash: Int, defDigest: String): DisjointRangeMap<String>?

    /** Stores (or overwrites) the resolution for this (study set, source) with its validity stamps. */
    fun put(studySet: String, sourceKey: String, textHash: Int, defDigest: String, resolution: DisjointRangeMap<String>)
}

class PostgresAnnotationCache(private val db: Database) : AnnotationCache {

    override fun get(studySet: String, sourceKey: String, textHash: Int, defDigest: String): DisjointRangeMap<String>? =
        transaction(db) {
            TextAnnotationsTable.selectAll()
                .where {
                    (TextAnnotationsTable.studySet eq studySet) and
                        (TextAnnotationsTable.sourceKey eq sourceKey) and
                        (TextAnnotationsTable.textHash eq textHash) and
                        (TextAnnotationsTable.defDigest eq defDigest)
                }
                .singleOrNull()
                ?.let { deserialize(it[TextAnnotationsTable.body]) }
        }

    override fun put(
        studySet: String,
        sourceKey: String,
        textHash: Int,
        defDigest: String,
        resolution: DisjointRangeMap<String>,
    ) {
        transaction(db) {
            TextAnnotationsTable.upsert(TextAnnotationsTable.studySet, TextAnnotationsTable.sourceKey) {
                it[TextAnnotationsTable.studySet] = studySet
                it[TextAnnotationsTable.sourceKey] = sourceKey
                it[TextAnnotationsTable.textHash] = textHash
                it[TextAnnotationsTable.defDigest] = defDigest
                it[body] = serialize(resolution)
            }
        }
    }

    companion object {
        /** One `start<TAB>end<TAB>value` line per entry, in ascending offset order. */
        fun serialize(map: DisjointRangeMap<String>): String =
            map.entries.joinToString("\n") { (range, value) -> "${range.first}\t${range.last}\t$value" }

        fun deserialize(body: String): DisjointRangeMap<String> = DisjointRangeMap<String>().apply {
            body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val (start, end, value) = line.split('\t', limit = 3)
                put(start.toInt()..end.toInt(), value)
            }
        }
    }
}
