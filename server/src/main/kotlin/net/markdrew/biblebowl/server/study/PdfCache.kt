package net.markdrew.biblebowl.server.study

import net.markdrew.biblebowl.server.data.GeneratedPdfsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Persists compiled season-text PDFs so a repeat /generate request with the same params serves the
 * stored bytes instead of re-running Typst. Keyed by the canonical param-encoded filename (see
 * shared-api's PdfFileNames); a row is valid only while [contentStamp] matches the current study
 * text + word-list digest, so a season rollover or curated-list edit auto-invalidates. Generation-
 * code changes are handled by the admin `DELETE /generate/cache`.
 */
interface PdfCache {
    /** Returns the cached PDF, or null if absent or stale (stamp mismatch). */
    fun get(studySet: String, fileName: String, contentStamp: Int): ByteArray?

    /** Stores (or overwrites) the compiled PDF for this (study set, filename). */
    fun put(studySet: String, fileName: String, contentStamp: Int, pdf: ByteArray)

    /** Drops every cached PDF; returns how many were removed. */
    fun clear(): Int
}

class PostgresPdfCache(private val db: Database) : PdfCache {

    override fun get(studySet: String, fileName: String, contentStamp: Int): ByteArray? =
        transaction(db) {
            GeneratedPdfsTable.selectAll()
                .where {
                    (GeneratedPdfsTable.studySet eq studySet) and
                        (GeneratedPdfsTable.fileName eq fileName) and
                        (GeneratedPdfsTable.contentStamp eq contentStamp)
                }
                .singleOrNull()
                ?.get(GeneratedPdfsTable.body)
        }

    override fun put(studySet: String, fileName: String, contentStamp: Int, pdf: ByteArray) {
        transaction(db) {
            GeneratedPdfsTable.upsert(GeneratedPdfsTable.studySet, GeneratedPdfsTable.fileName) {
                it[GeneratedPdfsTable.studySet] = studySet
                it[GeneratedPdfsTable.fileName] = fileName
                it[GeneratedPdfsTable.contentStamp] = contentStamp
                it[createdAtEpochMs] = System.currentTimeMillis()
                it[body] = pdf
            }
        }
    }

    override fun clear(): Int = transaction(db) { GeneratedPdfsTable.deleteAll() }
}

/**
 * No-DB fallback (local dev) and test double. Bounded (LRU-ish, insertion order) because bible-text
 * PDFs run to megabytes and the option combinations would otherwise balloon a long-lived dev server.
 */
class InMemoryPdfCache(private val maxEntries: Int = 32) : PdfCache {

    private val entries = object : LinkedHashMap<Pair<String, String>, Pair<Int, ByteArray>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Pair<Int, ByteArray>>) =
            size > maxEntries
    }

    @Synchronized
    override fun get(studySet: String, fileName: String, contentStamp: Int): ByteArray? =
        entries[studySet to fileName]?.takeIf { it.first == contentStamp }?.second

    @Synchronized
    override fun put(studySet: String, fileName: String, contentStamp: Int, pdf: ByteArray) {
        entries[studySet to fileName] = contentStamp to pdf
    }

    @Synchronized
    override fun clear(): Int = entries.size.also { entries.clear() }
}
