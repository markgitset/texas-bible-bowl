package net.markdrew.biblebowl.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Study & Practice sections — the one canonical list, shared by the server (the
 * `study_materials.section` column), both apps' `study/<slug>` routes, and — via the generated
 * site/data/study-sections.json (`./gradlew :shared-api:generateStudySectionsData`; a jvmTest
 * fails CI while it's stale) — the site navbar's dropdown and the Site Map. Serialized as the
 * slug, so the wire format, the URL fragment, and the stored value are the same string.
 */
@Serializable
enum class StudySection(val slug: String, val title: String) {
    @SerialName("the-text") TEXT("the-text", "The Text"),
    @SerialName("general-knowledge") GENERAL("general-knowledge", "General Knowledge"),
    @SerialName("chapter-headings") HEADINGS("chapter-headings", "Chapter Headings"),
    @SerialName("unique-words") UNIQUE_WORDS("unique-words", "Unique Words"),
    @SerialName("practice-tests") PRACTICE_TESTS("practice-tests", "Practice Tests"),
    @SerialName("reference-documents") REFERENCE("reference-documents", "Reference Documents"),
    @SerialName("data-source-files") DATA("data-source-files", "Data & Source Files");

    companion object {
        /** Strict slug lookup, like StandardStudySet.bySlug — never a lenient parse. */
        fun bySlug(slug: String?): StudySection? = entries.firstOrNull { it.slug == slug }
    }
}
