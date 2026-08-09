package net.markdrew.biblebowl.web.screens

import kotlinx.coroutines.launch
import net.markdrew.biblebowl.api.StudyMaterialDto
import net.markdrew.biblebowl.api.StudyMaterialType
import net.markdrew.biblebowl.api.StudyMaterialsResponse
import net.markdrew.biblebowl.api.StudySection
import net.markdrew.biblebowl.api.UpsertStudyMaterialRequest
import net.markdrew.biblebowl.api.filePath
import net.markdrew.biblebowl.model.StandardStudySet
import net.markdrew.biblebowl.web.Session
import net.markdrew.biblebowl.web.Shell
import net.markdrew.biblebowl.web.child
import net.markdrew.biblebowl.web.clear
import net.markdrew.biblebowl.web.errorLine
import net.markdrew.biblebowl.web.friendlyError
import net.markdrew.biblebowl.web.onClick
import net.markdrew.biblebowl.web.spinner
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get
import kotlinx.browser.window

/** Mirrors the server's cap so most oversized picks fail fast with a friendly message. */
private const val MAX_UPLOAD_BYTES = 25 * 1024 * 1024

/**
 * Admin management of the study materials shown on the Study & Practice section pages: upload a
 * document (served back exactly as uploaded — past tests and other hand-made files) or add an
 * external link (Kahoot, Quizlet, …), each with a title + description, pinned to a study set and
 * one section, in a manually ordered list. Gated on SEASON_MANAGE (Shell routes it like Season
 * settings); what users see is DownloadsScreen rendering the same list after its built-in cards.
 */
object AdminMaterialsScreen {

    private var selectedSet: String = Session.studySet.simpleName
    private var materials: List<StudyMaterialDto>? = null
    private var message: String? = null
    private var editingId: String? = null
    private lateinit var content: HTMLElement

    fun render(container: HTMLElement) {
        container.child("h1", "page-title", "Study Materials")
        content = container.child("div")
        content.spinner()
        materials = null
        message = null
        editingId = null
        selectedSet = Session.studySet.simpleName
        fetch()
    }

    private fun fetch() {
        Shell.scope.launch {
            try {
                materials = Session.api.studyMaterials(set = selectedSet)
                message = null
            } catch (e: Throwable) {
                content.clear()
                content.errorLine(e)
                return@launch
            }
            renderContent()
        }
    }

    /** Runs a mutation, refreshes from its [StudyMaterialsResponse], and re-renders. */
    private fun mutate(block: suspend () -> StudyMaterialsResponse) {
        Shell.scope.launch {
            try {
                materials = block().materials
                message = null
            } catch (e: Throwable) {
                message = friendlyError(e)
            }
            renderContent()
        }
    }

    private fun renderContent() {
        val loaded = materials ?: return
        content.clear()
        content.child(
            "p", "text-muted small",
            "Documents are served back exactly as uploaded (past tests, keys, hand-made helps); links " +
                "open the external site. Everything here appears on the chosen Study & Practice section " +
                "page for the chosen study set, below the built-in cards, in the order shown.",
        )
        renderSetPicker()
        message?.let { content.errorLine(it) }

        val bySection = loaded.groupBy { it.section }
        StudySection.entries.forEach { section ->
            val sectionMaterials = bySection[section] ?: return@forEach
            content.child("h4", "mt-4", section.title)
            content.child("ul", "list-group") {
                sectionMaterials.forEachIndexed { index, m ->
                    child("li", "list-group-item") {
                        if (editingId == m.id) renderEditor(this, m)
                        else renderRow(this, m, sectionMaterials, index)
                    }
                }
            }
        }
        if (loaded.isEmpty()) content.child("p", "text-muted mt-3", "No materials for this study set yet.")

        content.child("h4", "mt-4", "Add a material")
        renderAddForm(content.child("div", "card") { }.child("div", "card-body"))
    }

    private fun renderSetPicker() {
        content.child("div", "mb-3") {
            child("label", "form-label", "Study set")
            val select = child("select", "form-select w-auto") as HTMLSelectElement
            StandardStudySet.entries.forEach { standard ->
                val option = select.child("option", text = standard.set.name) as HTMLOptionElement
                option.value = standard.set.simpleName
                if (standard.set.simpleName == selectedSet) option.selected = true
            }
            select.addEventListener("change", {
                selectedSet = select.value
                materials = null
                editingId = null
                content.clear()
                content.spinner()
                fetch()
            })
        }
    }

    private fun renderRow(parent: Element, m: StudyMaterialDto, siblings: List<StudyMaterialDto>, index: Int) {
        parent.child("div", "d-flex align-items-start gap-2 flex-wrap") {
            child("div", "me-auto") {
                child("div") {
                    child("span", "fw-semibold", m.title)
                    append(" ")
                    when (m.type) {
                        StudyMaterialType.DOCUMENT -> child("span", "badge text-bg-secondary", "Document")
                        StudyMaterialType.LINK -> child("span", "badge text-bg-info", "Link")
                    }
                }
                if (m.description.isNotBlank()) child("div", "text-muted small", m.description)
                when (m.type) {
                    StudyMaterialType.DOCUMENT ->
                        child("a", "small", "${m.fileName}${m.fileSize?.let { " (${formatSize(it)})" } ?: ""}") {
                            setAttribute("href", Session.api.baseUrl + m.filePath())
                            setAttribute("target", "_blank")
                            setAttribute("rel", "noopener")
                        }
                    StudyMaterialType.LINK ->
                        child("a", "small", m.url ?: "") {
                            setAttribute("href", m.url ?: "#")
                            setAttribute("target", "_blank")
                            setAttribute("rel", "noopener")
                        }
                }
            }
            // Manual order: swap with the neighbour and send the section's full id list.
            moveButton(this, "↑", enabled = index > 0) { reorder(siblings, index, index - 1) }
            moveButton(this, "↓", enabled = index < siblings.size - 1) { reorder(siblings, index, index + 1) }
            child("button", "btn btn-outline-secondary btn-sm", "Edit") {
                setAttribute("type", "button")
                onClick { editingId = m.id; renderContent() }
            }
            child("button", "btn btn-outline-danger btn-sm", "Delete") {
                setAttribute("type", "button")
                onClick {
                    if (window.confirm("Delete \"${m.title}\"? This can't be undone.")) {
                        mutate { Session.api.deleteStudyMaterial(m.id) }
                    }
                }
            }
        }
    }

    private fun moveButton(parent: Element, label: String, enabled: Boolean, onMove: () -> Unit) {
        parent.child("button", "btn btn-outline-secondary btn-sm", label) {
            setAttribute("type", "button")
            setAttribute("aria-label", if (label == "↑") "Move up" else "Move down")
            if (!enabled) setAttribute("disabled", "")
            else onClick(onMove)
        }
    }

    private fun reorder(siblings: List<StudyMaterialDto>, from: Int, to: Int) {
        val ids = siblings.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(from))
        mutate { Session.api.reorderStudyMaterials(ids) }
    }

    /** Inline metadata editor (title, description, section, and the URL for links). */
    private fun renderEditor(parent: Element, m: StudyMaterialDto) {
        val title = parent.labeledInput("Title", m.title)
        val description = parent.labeledTextarea("Description", m.description)
        val section = parent.sectionSelect(m.section)
        val url = if (m.type == StudyMaterialType.LINK) parent.labeledInput("URL", m.url ?: "") else null
        parent.child("div", "d-flex gap-2 mt-2") {
            child("button", "btn btn-primary btn-sm", "Save") {
                setAttribute("type", "button")
                onClick {
                    editingId = null
                    mutate {
                        Session.api.updateStudyMaterial(
                            m.id,
                            UpsertStudyMaterialRequest(
                                studySet = m.studySet,
                                section = StudySection.bySlug(section.value) ?: m.section,
                                type = m.type,
                                title = title.value,
                                description = description.value,
                                url = url?.value,
                            ),
                        )
                    }
                }
            }
            child("button", "btn btn-outline-secondary btn-sm", "Cancel") {
                setAttribute("type", "button")
                onClick { editingId = null; renderContent() }
            }
        }
    }

    // --- the add form -----------------------------------------------------------------------

    private var addAsLink = false

    private fun renderAddForm(form: HTMLElement) {
        form.child("div", "d-flex gap-1 mb-3") {
            typeChip(this, "Upload a file", !addAsLink) { addAsLink = false; renderContent() }
            typeChip(this, "External link", addAsLink) { addAsLink = true; renderContent() }
        }
        val title = form.labeledInput("Title", "")
        val description = form.labeledTextarea("Description (shown under the title)", "")
        val section = form.sectionSelect(StudySection.PRACTICE_TESTS)
        var fileInput: HTMLInputElement? = null
        var urlInput: HTMLInputElement? = null
        if (addAsLink) {
            urlInput = form.labeledInput("URL (https://…)", "")
        } else {
            form.child("div", "mb-3") {
                child("label", "form-label", "File (served back exactly as uploaded)")
                fileInput = (child("input", "form-control") as HTMLInputElement).apply { type = "file" }
            }
        }
        val slot = form.child("div")
        form.child("button", "btn btn-primary", "Add material") {
            setAttribute("type", "button")
            onClick {
                slot.clear()
                val req = UpsertStudyMaterialRequest(
                    studySet = selectedSet,
                    section = StudySection.bySlug(section.value) ?: StudySection.PRACTICE_TESTS,
                    type = if (addAsLink) StudyMaterialType.LINK else StudyMaterialType.DOCUMENT,
                    title = title.value,
                    description = description.value,
                    url = urlInput?.value?.takeIf { it.isNotBlank() },
                )
                if (title.value.isBlank()) return@onClick slot.errorLine("A material needs a title.")
                if (addAsLink) {
                    mutate { Session.api.createLinkMaterial(req) }
                } else {
                    val file = fileInput?.files?.get(0)
                        ?: return@onClick slot.errorLine("Pick a file to upload.")
                    if (file.size.toDouble() > MAX_UPLOAD_BYTES) {
                        return@onClick slot.errorLine("Uploads are capped at 25 MB.")
                    }
                    uploadDocument(req, file)
                }
            }
        }
    }

    private fun typeChip(parent: Element, label: String, selected: Boolean, onSelect: () -> Unit) {
        parent.child(
            "button",
            "btn btn-sm rounded-pill " + (if (selected) "btn-primary" else "btn-outline-primary"),
            label,
        ) {
            setAttribute("type", "button")
            onClick(onSelect)
        }
    }

    /** Reads the picked file's bytes in the browser, then posts the one multipart request. */
    private fun uploadDocument(req: UpsertStudyMaterialRequest, file: File) {
        val reader = FileReader()
        reader.onload = {
            val bytes = Int8Array(reader.result as ArrayBuffer).unsafeCast<ByteArray>()
            mutate {
                Session.api.createDocumentMaterial(
                    req,
                    fileName = file.name,
                    fileContentType = file.type.ifBlank { "application/octet-stream" },
                    bytes = bytes,
                )
            }
        }
        reader.onerror = { message = "Could not read the file."; renderContent() }
        reader.readAsArrayBuffer(file)
    }

    // --- small form helpers -----------------------------------------------------------------

    private fun Element.labeledInput(label: String, value: String): HTMLInputElement {
        lateinit var input: HTMLInputElement
        child("div", "mb-3") {
            child("label", "form-label", label)
            input = child("input", "form-control") as HTMLInputElement
            input.value = value
        }
        return input
    }

    private fun Element.labeledTextarea(label: String, value: String): HTMLTextAreaElement {
        lateinit var area: HTMLTextAreaElement
        child("div", "mb-3") {
            child("label", "form-label", label)
            area = child("textarea", "form-control") as HTMLTextAreaElement
            area.setAttribute("rows", "2")
            area.value = value
        }
        return area
    }

    private fun Element.sectionSelect(selected: StudySection): HTMLSelectElement {
        lateinit var select: HTMLSelectElement
        child("div", "mb-3") {
            child("label", "form-label", "Study & Practice section")
            select = child("select", "form-select w-auto") as HTMLSelectElement
            StudySection.entries.forEach { section ->
                val option = select.child("option", text = section.title) as HTMLOptionElement
                option.value = section.slug
                if (section == selected) option.selected = true
            }
        }
        return select
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${(bytes * 10 / (1024 * 1024)).toDouble() / 10} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }
}
