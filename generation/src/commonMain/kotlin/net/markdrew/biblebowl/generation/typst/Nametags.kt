package net.markdrew.biblebowl.generation.typst

/**
 * One printable nametag: the attendee's [name] and [congregation], an optional [role] line
 * (division for testers, "Guest"/"Volunteer" for non-contestants), and for testers the [testerId]
 * (item 13, F7 — printed big for test-day seating) with the full [externalId] (the scan-sheet /
 * ZipGrade ID) in small print above it.
 */
data class Nametag(
    val name: String,
    val congregation: String,
    val role: String = "",
    val testerId: Int? = null,
    val externalId: String? = null,
)

/**
 * One site's worth of nametags: [heading] is stamped on every badge (event + site, e.g.
 * "Texas Bible Bowl 2027 — Bandina") and each sheet starts on a fresh page, so a multi-site
 * season prints as separable per-site stacks.
 */
data class NametagSheet(
    val heading: String,
    val tags: List<Nametag>,
)

/**
 * Emits printable nametags as Typst source (registration backlog item 14, F8 — replaces the
 * workbook's four nametag tabs): 4in × 3in badges (standard badge-insert size), six per US-Letter
 * page with dashed cut guides. Each badge shows the sheet heading, the attendee's name and
 * congregation, the role at bottom-left, and the tester ID big at bottom-right with the external
 * (scan-sheet) ID in small print above it.
 */
fun nametagsTypst(sheets: List<NametagSheet>): String = buildString {
    appendLine(
        """
        #let badge_width = 4in
        #let badge_height = 3in
        #let columns = 2
        #let rows = 3
        #let badges_per_page = columns * rows

        #let sheets = (
        """.trimIndent()
    )

    sheets.forEach { sheet ->
        appendLine("""(heading: "${escapeTypstString(sheet.heading)}", tags: (""")
        sheet.tags.forEach { tag ->
            appendLine(
                """(name: "${escapeTypstString(tag.name)}", """ +
                    """congregation: "${escapeTypstString(tag.congregation)}", """ +
                    """role: "${escapeTypstString(tag.role)}", """ +
                    """tester: "${tag.testerId?.toString() ?: ""}", """ +
                    """external: "${escapeTypstString(tag.externalId ?: "")}"),"""
            )
        }
        appendLine(")),")
    }

    appendLine(
        """
        )

        #let badge(x, y, heading, tag) = {
          place(
            top + left,
            dx: x,
            dy: y,
            rect(
              width: badge_width,
              height: badge_height,
              stroke: (paint: gray, thickness: 0.5pt, dash: "dashed"),
              inset: 0.15in,
              [
                #align(center, text(size: 9pt, fill: gray, heading))
                #align(center + horizon)[
                  #text(size: 24pt, weight: "bold", tag.name)
                  #v(0.12in)
                  #text(size: 13pt, tag.congregation)
                  #v(0.2in)
                ]
                #if tag.role != "" {
                  place(bottom + left, text(size: 10pt, fill: gray, tag.role))
                }
                #if tag.tester != "" {
                  let number = text(size: 16pt, weight: "bold", "#" + tag.tester)
                  place(bottom + right, if tag.external != "" {
                    stack(
                      spacing: 4pt,
                      align(right, text(size: 8pt, fill: gray, tag.external)),
                      align(right, number),
                    )
                  } else {
                    number
                  })
                }
              ]
            )
          )
        }

        #for sheet in sheets {
          let total_pages = calc.max(1, calc.ceil(sheet.tags.len() / badges_per_page))
          for page_num in range(0, total_pages) {
            page(width: 8.5in, height: 11in, margin: (x: 0.25in, y: 0.5in))[
              #align(center + horizon)[
                #block(
                  width: columns * badge_width,
                  height: rows * badge_height,
                )[
                  #for row in range(0, rows) {
                    for col in range(0, columns) {
                      let i = page_num * badges_per_page + row * columns + col
                      if i < sheet.tags.len() {
                        badge(col * badge_width, row * badge_height, sheet.heading, sheet.tags.at(i))
                      }
                    }
                  }
                ]
              ]
            ]
          }
        }
        """.trimIndent()
    )
}
