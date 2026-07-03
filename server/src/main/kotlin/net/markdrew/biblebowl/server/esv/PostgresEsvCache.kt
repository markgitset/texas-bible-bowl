package net.markdrew.biblebowl.server.esv

import net.markdrew.biblebowl.model.ChapterRef
import net.markdrew.biblebowl.server.data.EsvChaptersTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class PostgresEsvCache(private val db: Database) : EsvCache {

    override fun get(chapterRef: ChapterRef): CachedChapter? = transaction(db) {
        EsvChaptersTable.selectAll()
            .where {
                (EsvChaptersTable.bookCode eq chapterRef.book.name) and
                    (EsvChaptersTable.chapter eq chapterRef.chapter)
            }
            .singleOrNull()
            ?.let {
                CachedChapter(
                    bookCode = it[EsvChaptersTable.bookCode],
                    chapter = it[EsvChaptersTable.chapter],
                    canonical = it[EsvChaptersTable.canonical],
                    text = it[EsvChaptersTable.body],
                )
            }
    }

    override fun put(chapter: CachedChapter) {
        transaction(db) {
            EsvChaptersTable.insertIgnore {
                it[bookCode] = chapter.bookCode
                it[EsvChaptersTable.chapter] = chapter.chapter
                it[canonical] = chapter.canonical
                it[body] = chapter.text
            }
        }
    }
}
