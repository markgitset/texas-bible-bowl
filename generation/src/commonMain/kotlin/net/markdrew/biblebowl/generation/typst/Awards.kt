package net.markdrew.biblebowl.generation.typst

/**
 * One line of an awards page: a [place], the [name] (a contestant or a team), an optional [detail]
 * line (the congregation on an individual row; blank on a team row, whose [members] carry the
 * roster), a pre-formatted [score] ("180 / 250"), and — on a team row — the [members] names.
 */
data class AwardRow(
    val place: Int,
    val name: String,
    val detail: String = "",
    val score: String,
    val members: List<String> = emptyList(),
)

/** One division bracket's awards: its [title] plus the ranked [individuals] and [teams] to read out. */
data class AwardBracket(
    val title: String,
    val individuals: List<AwardRow>,
    val teams: List<AwardRow>,
)

/** One site's awards, its own page stack: a [heading] (the site name) and its [brackets]. */
data class AwardSite(
    val heading: String,
    val brackets: List<AwardBracket>,
)

/**
 * Emits the awards / ceremony booklet as Typst source (grading backlog G5) — the emcee's script,
 * replacing the workbook's seven Top-10 sheets. One page stack per site (a `pagebreak` between
 * sites), each bracket listing its individuals then teams in the order given (the caller sorts for
 * reverse announcement order — 10th read first, champion last). Team rows spell out their members.
 */
fun awardsTypst(title: String, sites: List<AwardSite>): String = buildString {
    appendLine("#set page(width: 8.5in, height: 11in, margin: 0.75in)")
    appendLine("#set text(size: 11pt)")
    appendLine("""#let doc_title = "${escapeTypstString(title)}"""")
    appendLine(
        """
        #let award_table(rows, is_team) = table(
          columns: (auto, 1fr, auto),
          inset: 5pt,
          align: (center + horizon, left + horizon, right + horizon),
          table.header([*Place*], [*Name*], [*Points*]),
          ..rows.map(r => (
            [#r.place],
            if is_team and r.members != "" {
              [*#r.name* \ #text(size: 9pt, fill: gray, r.members)]
            } else if r.detail != "" {
              [#r.name #h(4pt) #text(size: 9pt, fill: gray, "— " + r.detail)]
            } else {
              [#r.name]
            },
            [#r.score],
          )).flatten()
        )
        """.trimIndent()
    )
    appendLine("#let sites = (")
    sites.forEach { site ->
        appendLine("""  (heading: "${escapeTypstString(site.heading)}", brackets: (""")
        site.brackets.forEach { bracket ->
            appendLine("""    (title: "${escapeTypstString(bracket.title)}",""")
            appendLine("      individuals: (${bracket.individuals.joinToString("") { rowLiteral(it) }}),")
            appendLine("      teams: (${bracket.teams.joinToString("") { rowLiteral(it) }})),")
        }
        appendLine("  )),")
    }
    appendLine(")")
    appendLine(
        """
        #for (si, site) in sites.enumerate() {
          if si > 0 { pagebreak() }
          align(center, text(size: 18pt, weight: "bold", doc_title))
          v(2pt)
          align(center, text(size: 13pt, site.heading))
          v(8pt)
          for bracket in site.brackets {
            heading(level: 2, bracket.title)
            if bracket.individuals.len() > 0 [ #text(weight: "bold")[Individuals] #award_table(bracket.individuals, false) #v(4pt) ]
            if bracket.teams.len() > 0 [ #text(weight: "bold")[Teams] #award_table(bracket.teams, true) #v(4pt) ]
          }
        }
        """.trimIndent()
    )
}

private fun rowLiteral(r: AwardRow): String =
    "(place: ${r.place}, " +
        """name: "${escapeTypstString(r.name)}", """ +
        """detail: "${escapeTypstString(r.detail)}", """ +
        """score: "${escapeTypstString(r.score)}", """ +
        """members: "${escapeTypstString(r.members.joinToString(", "))}"), """
