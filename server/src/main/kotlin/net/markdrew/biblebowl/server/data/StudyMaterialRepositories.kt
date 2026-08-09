package net.markdrew.biblebowl.server.data

import net.markdrew.biblebowl.api.StudyMaterialDto
import net.markdrew.biblebowl.api.StudyMaterialType
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.api.UpsertStudyMaterialRequest
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A DOCUMENT material's stored payload, byte-exact as uploaded. */
class StudyMaterialFile(val fileName: String, val contentType: String, val bytes: ByteArray)

/**
 * Admin-curated study materials (see StudyMaterialRoutes): metadata listings for the public
 * section pages plus the one by-id bytes read for downloads. Ordering is manual — creates append
 * within their (studySet, section) group and [reorder] rewrites positions from a full id list.
 */
interface StudyMaterialRepository {
    /** Metadata only, in display order — implementations must never read the body column here. */
    fun list(studySet: String? = null, section: StudySection? = null): List<StudyMaterialDto>
    fun get(id: String): StudyMaterialDto?
    /** The one BYTEA read, by primary key; null for unknown ids and LINK rows. */
    fun file(id: String): StudyMaterialFile?
    fun createLink(req: UpsertStudyMaterialRequest, createdByUserId: String): StudyMaterialDto
    fun createDocument(
        req: UpsertStudyMaterialRequest,
        file: StudyMaterialFile,
        createdByUserId: String,
    ): StudyMaterialDto
    /** Metadata-only edit (never touches the stored file); null for unknown ids. */
    fun update(id: String, req: UpsertStudyMaterialRequest): StudyMaterialDto?
    /** Sets sortPosition to each id's index in [orderedIds]; false if any id is unknown. */
    fun reorder(orderedIds: List<String>): Boolean
    fun delete(id: String): Boolean
}

private val MATERIAL_ORDER =
    compareBy<StudyMaterialDto>({ it.section }, { it.sortPosition }, { it.title.lowercase() })

// ---------------------------------------------------------------------------
// In-memory implementation (no DATABASE_URL: local dev & tests)
// ---------------------------------------------------------------------------

class InMemoryStudyMaterialRepository : StudyMaterialRepository {
    private data class Material(val dto: StudyMaterialDto, val file: StudyMaterialFile?)

    private val materials = ConcurrentHashMap<String, Material>()

    override fun list(studySet: String?, section: StudySection?): List<StudyMaterialDto> =
        materials.values.map { it.dto }
            .filter { studySet == null || it.studySet == studySet }
            .filter { section == null || it.section == section }
            .sortedWith(MATERIAL_ORDER)

    override fun get(id: String): StudyMaterialDto? = materials[id]?.dto

    override fun file(id: String): StudyMaterialFile? = materials[id]?.file

    private fun nextPosition(req: UpsertStudyMaterialRequest): Int =
        materials.values.filter { it.dto.studySet == req.studySet && it.dto.section == req.section }
            .maxOfOrNull { it.dto.sortPosition + 1 } ?: 0

    override fun createLink(req: UpsertStudyMaterialRequest, createdByUserId: String): StudyMaterialDto {
        val dto = StudyMaterialDto(
            id = UUID.randomUUID().toString(),
            studySet = req.studySet,
            section = req.section,
            type = StudyMaterialType.LINK,
            title = req.title.trim(),
            description = req.description.trim(),
            url = req.url,
            sortPosition = nextPosition(req),
        )
        materials[dto.id] = Material(dto, file = null)
        return dto
    }

    override fun createDocument(
        req: UpsertStudyMaterialRequest,
        file: StudyMaterialFile,
        createdByUserId: String,
    ): StudyMaterialDto {
        val dto = StudyMaterialDto(
            id = UUID.randomUUID().toString(),
            studySet = req.studySet,
            section = req.section,
            type = StudyMaterialType.DOCUMENT,
            title = req.title.trim(),
            description = req.description.trim(),
            fileName = file.fileName,
            contentType = file.contentType,
            fileSize = file.bytes.size.toLong(),
            sortPosition = nextPosition(req),
        )
        materials[dto.id] = Material(dto, file)
        return dto
    }

    override fun update(id: String, req: UpsertStudyMaterialRequest): StudyMaterialDto? {
        val existing = materials[id] ?: return null
        val dto = existing.dto.copy(
            studySet = req.studySet,
            section = req.section,
            title = req.title.trim(),
            description = req.description.trim(),
            url = if (existing.dto.type == StudyMaterialType.LINK) req.url else null,
        )
        materials[id] = existing.copy(dto = dto)
        return dto
    }

    override fun reorder(orderedIds: List<String>): Boolean {
        if (!orderedIds.all { materials.containsKey(it) }) return false
        orderedIds.forEachIndexed { index, id ->
            materials.computeIfPresent(id) { _, m -> m.copy(dto = m.dto.copy(sortPosition = index)) }
        }
        return true
    }

    override fun delete(id: String): Boolean = materials.remove(id) != null
}

// ---------------------------------------------------------------------------
// Postgres implementation
// ---------------------------------------------------------------------------

class PostgresStudyMaterialRepository(private val db: Database) : StudyMaterialRepository {

    /** Stored size without pulling the bytes: octet_length reads only the TOAST header. */
    private val fileSize = CustomFunction("octet_length", LongColumnType(), StudyMaterialsTable.body)

    /** Every listed column — everything but [StudyMaterialsTable.body], whose size stands in for it. */
    private val listedColumns = listOf(
        StudyMaterialsTable.id, StudyMaterialsTable.studySet, StudyMaterialsTable.section,
        StudyMaterialsTable.type, StudyMaterialsTable.title, StudyMaterialsTable.description,
        StudyMaterialsTable.url, StudyMaterialsTable.fileName, StudyMaterialsTable.contentType,
        StudyMaterialsTable.sortPosition, fileSize,
    )

    private fun ResultRow.toDto() = StudyMaterialDto(
        id = this[StudyMaterialsTable.id],
        studySet = this[StudyMaterialsTable.studySet],
        // Stored slugs are written from the enum, so a miss means a bad manual edit — fail loudly.
        section = checkNotNull(StudySection.bySlug(this[StudyMaterialsTable.section])) {
            "Unknown study section slug '${this[StudyMaterialsTable.section]}' in study_materials"
        },
        type = StudyMaterialType.valueOf(this[StudyMaterialsTable.type]),
        title = this[StudyMaterialsTable.title],
        description = this[StudyMaterialsTable.description],
        url = this[StudyMaterialsTable.url],
        fileName = this[StudyMaterialsTable.fileName],
        contentType = this[StudyMaterialsTable.contentType],
        fileSize = this[fileSize],
        sortPosition = this[StudyMaterialsTable.sortPosition],
    )

    override fun list(studySet: String?, section: StudySection?): List<StudyMaterialDto> = transaction(db) {
        var query = StudyMaterialsTable.select(listedColumns)
        if (studySet != null) query = query.where { StudyMaterialsTable.studySet eq studySet }
        if (section != null) query = query.andWhere { StudyMaterialsTable.section eq section.slug }
        query
            .orderBy(
                StudyMaterialsTable.section to SortOrder.ASC,
                StudyMaterialsTable.sortPosition to SortOrder.ASC,
                StudyMaterialsTable.title to SortOrder.ASC,
            )
            .map { it.toDto() }
    }

    override fun get(id: String): StudyMaterialDto? = transaction(db) {
        StudyMaterialsTable.select(listedColumns)
            .where { StudyMaterialsTable.id eq id }
            .singleOrNull()?.toDto()
    }

    override fun file(id: String): StudyMaterialFile? = transaction(db) {
        StudyMaterialsTable
            .select(StudyMaterialsTable.fileName, StudyMaterialsTable.contentType, StudyMaterialsTable.body)
            .where { StudyMaterialsTable.id eq id }
            .singleOrNull()
            ?.let { row ->
                StudyMaterialFile(
                    fileName = row[StudyMaterialsTable.fileName] ?: return@let null,
                    contentType = row[StudyMaterialsTable.contentType] ?: return@let null,
                    bytes = row[StudyMaterialsTable.body] ?: return@let null,
                )
            }
    }

    private fun nextPosition(req: UpsertStudyMaterialRequest): Int =
        StudyMaterialsTable.select(StudyMaterialsTable.sortPosition)
            .where { StudyMaterialsTable.studySet eq req.studySet }
            .andWhere { StudyMaterialsTable.section eq req.section.slug }
            .maxOfOrNull { it[StudyMaterialsTable.sortPosition] + 1 } ?: 0

    private fun create(
        req: UpsertStudyMaterialRequest,
        type: StudyMaterialType,
        file: StudyMaterialFile?,
        createdByUserId: String,
    ): StudyMaterialDto = transaction(db) {
        val materialId = UUID.randomUUID().toString()
        val position = nextPosition(req)
        StudyMaterialsTable.insert {
            it[id] = materialId
            it[studySet] = req.studySet
            it[section] = req.section.slug
            it[StudyMaterialsTable.type] = type.name
            it[title] = req.title.trim()
            it[description] = req.description.trim()
            it[url] = req.url.takeIf { type == StudyMaterialType.LINK }
            it[fileName] = file?.fileName
            it[contentType] = file?.contentType
            it[body] = file?.bytes
            it[sortPosition] = position
            it[createdAtEpochMs] = System.currentTimeMillis()
            it[StudyMaterialsTable.createdByUserId] = createdByUserId
        }
        StudyMaterialDto(
            id = materialId,
            studySet = req.studySet,
            section = req.section,
            type = type,
            title = req.title.trim(),
            description = req.description.trim(),
            url = req.url.takeIf { type == StudyMaterialType.LINK },
            fileName = file?.fileName,
            contentType = file?.contentType,
            fileSize = file?.bytes?.size?.toLong(),
            sortPosition = position,
        )
    }

    override fun createLink(req: UpsertStudyMaterialRequest, createdByUserId: String): StudyMaterialDto =
        create(req, StudyMaterialType.LINK, file = null, createdByUserId = createdByUserId)

    override fun createDocument(
        req: UpsertStudyMaterialRequest,
        file: StudyMaterialFile,
        createdByUserId: String,
    ): StudyMaterialDto = create(req, StudyMaterialType.DOCUMENT, file, createdByUserId)

    override fun update(id: String, req: UpsertStudyMaterialRequest): StudyMaterialDto? = transaction(db) {
        val existing = StudyMaterialsTable.select(StudyMaterialsTable.type)
            .where { StudyMaterialsTable.id eq id }
            .singleOrNull() ?: return@transaction null
        val type = StudyMaterialType.valueOf(existing[StudyMaterialsTable.type])
        StudyMaterialsTable.update({ StudyMaterialsTable.id eq id }) {
            it[studySet] = req.studySet
            it[section] = req.section.slug
            it[title] = req.title.trim()
            it[description] = req.description.trim()
            if (type == StudyMaterialType.LINK) it[url] = req.url
        }
        StudyMaterialsTable.select(listedColumns).where { StudyMaterialsTable.id eq id }.single().toDto()
    }

    override fun reorder(orderedIds: List<String>): Boolean = transaction(db) {
        val known = StudyMaterialsTable.select(StudyMaterialsTable.id)
            .where { StudyMaterialsTable.id inList orderedIds }
            .mapTo(mutableSetOf()) { it[StudyMaterialsTable.id] }
        if (!orderedIds.all { it in known }) return@transaction false
        // A handful of updates at most — a section's list is short and reorders are rare admin acts.
        orderedIds.forEachIndexed { index, materialId ->
            StudyMaterialsTable.update({ StudyMaterialsTable.id eq materialId }) {
                it[sortPosition] = index
            }
        }
        true
    }

    override fun delete(id: String): Boolean = transaction(db) {
        StudyMaterialsTable.deleteWhere { StudyMaterialsTable.id eq id } > 0
    }
}
