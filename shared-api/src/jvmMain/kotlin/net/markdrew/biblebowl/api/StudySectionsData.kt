package net.markdrew.biblebowl.api

import java.io.File

/**
 * The content of `site/data/study-sections.json` — the Hugo-side mirror of [StudySection] that
 * nav.html (the Study & Practice dropdown) and sitemap.html render their study links from. The
 * file is generated (`./gradlew :shared-api:generateStudySectionsData`) and checked in so plain
 * Hugo builds need no Gradle step; StudySectionsDataTest fails the build if it drifts.
 */
fun studySectionsJson(): String = buildString {
    appendLine("{")
    appendLine(
        "  \"comment\": \"GENERATED from shared-api's StudySection enum — do not edit. " +
            "Regenerate with: ./gradlew :shared-api:generateStudySectionsData\","
    )
    appendLine("  \"sections\": [")
    StudySection.entries.forEachIndexed { i, section ->
        val comma = if (i < StudySection.entries.lastIndex) "," else ""
        appendLine("""    { "slug": "${section.slug}", "title": "${section.title}" }$comma""")
    }
    appendLine("  ]")
    appendLine("}")
}

fun main(args: Array<String>) {
    val target = File(args.singleOrNull() ?: error("usage: StudySectionsDataKt <path-to-study-sections.json>"))
    target.writeText(studySectionsJson())
    println("Wrote ${StudySection.entries.size} sections to $target")
}
