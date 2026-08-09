package net.markdrew.biblebowl.server.routes

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import net.markdrew.biblebowl.api.ApiError
import net.markdrew.biblebowl.api.Permission
import net.markdrew.biblebowl.api.ReorderStudyMaterialsRequest
import net.markdrew.biblebowl.api.StudyMaterialType
import net.markdrew.biblebowl.api.StudyMaterialsResponse
import net.markdrew.biblebowl.api.StudyScopeParams
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.api.UpsertStudyMaterialRequest
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.server.data.StudyMaterialFile
import net.markdrew.biblebowl.server.data.StudyMaterialRepository
import net.markdrew.biblebowl.server.data.UserRepository
import net.markdrew.biblebowl.server.security.currentUser
import net.markdrew.biblebowl.server.security.requirePermission

/** Upload cap — past tests are a few MB of PDF; anything bigger is probably a mistake. */
private const val MAX_UPLOAD_BYTES_DEFAULT = 25 * 1024 * 1024

private val metadataJson = Json { ignoreUnknownKeys = true }

/**
 * Admin-curated study materials: documents uploaded as-is (served back byte-exact) and external
 * links, pinned to a study set + Study & Practice section. Reads are public like every other GET;
 * mutations are gated on SEASON_MANAGE (like Season settings and the PDF-cache purge — a new
 * Permission value would break deployed clients deserializing UserDto.permissions).
 *
 * A LINK is created by POSTing [UpsertStudyMaterialRequest] as JSON; a DOCUMENT by POSTing
 * multipart/form-data with that request in a `metadata` part beside the `file` part. Edits are
 * metadata-only — replacing a file is delete + re-add.
 */
fun Route.studyMaterialRoutes(
    users: UserRepository,
    materials: StudyMaterialRepository,
    maxUploadBytes: Int = MAX_UPLOAD_BYTES_DEFAULT,
) {
    /** Validates the shared metadata shape; responds and returns false when invalid. */
    suspend fun RoutingContext.validMaterial(req: UpsertStudyMaterialRequest): Boolean {
        if (req.title.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, ApiError("invalid_material", "A material needs a title"))
            return false
        }
        if (StandardStudySet.bySlug(req.studySet) == null) {
            call.respond(HttpStatusCode.BadRequest, ApiError("unknown_set", "Unknown study set '${req.studySet}'"))
            return false
        }
        if (req.type == StudyMaterialType.LINK) {
            // An explicit scheme prefix, not just Url() parsing: Ktor defaults scheme-less strings
            // to http, which would wave through arbitrary text (and never reject javascript: etc).
            val url = req.url
            val valid = url != null &&
                (url.startsWith("http://") || url.startsWith("https://")) &&
                runCatching { Url(url) }.isSuccess
            if (!valid) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_url", "A link needs an http(s) URL"))
                return false
            }
        }
        return true
    }

    get("/study-materials") {
        val setParam = call.request.queryParameters[StudyScopeParams.SET]
        if (setParam != null && StandardStudySet.bySlug(setParam) == null) {
            return@get call.respond(HttpStatusCode.BadRequest, ApiError("unknown_set", "Unknown study set '$setParam'"))
        }
        val sectionParam = call.request.queryParameters[StudyScopeParams.SECTION]
        val section = sectionParam?.let {
            StudySection.bySlug(it) ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ApiError("unknown_section", "Unknown study section '$it'"),
            )
        }
        call.respond(materials.list(studySet = setParam, section = section))
    }

    get("/study-materials/{id}/file") {
        val file = materials.file(call.parameters["id"]!!) ?: return@get call.respond(
            HttpStatusCode.NotFound,
            ApiError("not_found", "No such document"),
        )
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment
                .withParameter(ContentDisposition.Parameters.FileName, file.fileName)
                .toString(),
        )
        // Byte-exact: the stored original, under its original content type (however odd).
        val contentType = runCatching { ContentType.parse(file.contentType) }
            .getOrDefault(ContentType.Application.OctetStream)
        call.respondBytes(file.bytes, contentType)
    }

    authenticate {
        suspend fun RoutingContext.admin(): Boolean {
            val user = currentUser(users) ?: return false
            return requirePermission(user, Permission.SEASON_MANAGE)
        }

        post("/study-materials") {
            val user = currentUser(users) ?: return@post
            if (!requirePermission(user, Permission.SEASON_MANAGE)) return@post

            if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                // JSON body: an external link.
                val req = call.receive<UpsertStudyMaterialRequest>()
                if (req.type != StudyMaterialType.LINK) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_material", "Upload documents as multipart/form-data"),
                    )
                }
                if (!validMaterial(req)) return@post
                materials.createLink(req, createdByUserId = user.id)
                return@post call.respond(StudyMaterialsResponse(materials.list(req.studySet)))
            }

            // Multipart: a document upload — a `metadata` JSON part beside the `file` part.
            call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { declared ->
                // Cheap early reject before reading anything (small allowance for part framing).
                if (declared > maxUploadBytes + 64 * 1024) {
                    return@post call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("file_too_large", "Uploads are capped at ${maxUploadBytes / (1024 * 1024)} MB"),
                    )
                }
            }
            var req: UpsertStudyMaterialRequest? = null
            var fileName: String? = null
            var fileContentType: String? = null
            var bytes: ByteArray? = null
            call.receiveMultipart().forEachPart { part ->
                when {
                    part is PartData.FormItem && part.name == "metadata" ->
                        req = runCatching { metadataJson.decodeFromString<UpsertStudyMaterialRequest>(part.value) }
                            .getOrNull()
                    part is PartData.FileItem && part.name == "file" -> {
                        fileName = part.originalFileName
                        fileContentType = part.contentType?.toString()
                        // Bounded read: Ktor has no server-wide body limit, so the cap lives here.
                        bytes = part.provider().readRemaining(maxUploadBytes + 1L).readByteArray()
                    }
                    else -> {}
                }
                part.dispose()
            }
            val metadata = req ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_material", "Missing or unreadable metadata part"),
            )
            if (metadata.type != StudyMaterialType.DOCUMENT) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("invalid_material", "Multipart uploads must be DOCUMENT materials"),
                )
            }
            if (!validMaterial(metadata)) return@post
            val body = bytes ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("invalid_material", "Missing file part"),
            )
            if (body.size > maxUploadBytes) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ApiError("file_too_large", "Uploads are capped at ${maxUploadBytes / (1024 * 1024)} MB"),
                )
            }
            val name = fileName?.takeIf { it.isNotBlank() } ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                ApiError("missing_file_name", "The uploaded file needs a filename"),
            )
            val file = StudyMaterialFile(
                fileName = name,
                contentType = fileContentType ?: ContentType.Application.OctetStream.toString(),
                bytes = body,
            )
            materials.createDocument(metadata, file, createdByUserId = user.id)
            call.respond(StudyMaterialsResponse(materials.list(metadata.studySet)))
        }

        // Literal path registered before the {id} sibling below so "order" never binds as an id
        // (Ktor prefers the literal segment either way; the order makes it obvious).
        put("/study-materials/order") {
            if (!admin()) return@put
            val req = call.receive<ReorderStudyMaterialsRequest>()
            val first = req.orderedIds.firstOrNull()?.let { materials.get(it) } ?: return@put call.respond(
                HttpStatusCode.BadRequest,
                ApiError("unknown_material", "Nothing to reorder"),
            )
            if (!materials.reorder(req.orderedIds)) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("unknown_material", "One or more ids don't exist"),
                )
            }
            call.respond(StudyMaterialsResponse(materials.list(first.studySet)))
        }

        put("/study-materials/{id}") {
            if (!admin()) return@put
            val req = call.receive<UpsertStudyMaterialRequest>()
            if (!validMaterial(req)) return@put
            val id = call.parameters["id"]!!
            val existing = materials.get(id) ?: return@put call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "No such material"),
            )
            if (existing.type != req.type) {
                return@put call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("type_immutable", "A material can't change type — delete and re-add instead"),
                )
            }
            materials.update(id, req)
            call.respond(StudyMaterialsResponse(materials.list(req.studySet)))
        }

        delete("/study-materials/{id}") {
            if (!admin()) return@delete
            val id = call.parameters["id"]!!
            val existing = materials.get(id) ?: return@delete call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "No such material"),
            )
            materials.delete(id)
            call.respond(StudyMaterialsResponse(materials.list(existing.studySet)))
        }
    }
}
