package net.markdrew.biblebowl.generation.typst

import net.markdrew.biblebowl.api.QuestionDto

/**
 * One duplex flashcard: [front] shows the prompt, [back] the answer, with an optional smaller
 * [note] under the answer (e.g. verse references) and a bottom-right [footer] (e.g. "3 of 40").
 */
data class Flashcard(
    val front: String,
    val back: String,
    val note: String = "",
    val footer: String = "",
)

/** Builds flashcards from approved community questions: prompt on the front, answer + refs on the back. */
fun List<QuestionDto>.toFlashcards(): List<Flashcard> = mapIndexed { i, q ->
    Flashcard(
        front = q.prompt,
        back = q.answer,
        note = q.references.joinToString("; "),
        footer = "${i + 1} of $size",
    )
}

/** Escapes Typst *string-literal* content (different from markup escaping): backslash and double quote. */
fun escapeTypstString(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Emits a duplex flashcard deck as Typst source, on 2×5 Avery-5870-style card stock.
 *
 * The page/layout machinery is ported verbatim from bible-bowl's typst/Flashcards.kt: fronts page then
 * mirrored backs page (columns reversed) so double-sided printing aligns.
 */
fun flashcardsTypst(cards: List<Flashcard>): String = buildString {
    appendLine(
        """
        #let card_width = 3.5in
        #let card_height = 2in
        #let columns = 2
        #let rows = 5
        #let cards_per_page = columns * rows

        #let flashcards = (
        """.trimIndent()
    )

    cards.forEach { card ->
        appendLine(
            """(question: "${escapeTypstString(card.front)}", """ +
                """answer: "${escapeTypstString(card.back)}", """ +
                """note: "${escapeTypstString(card.note)}", """ +
                """footer: "${escapeTypstString(card.footer)}"),"""
        )
    }

    appendLine(
        """
        )

        #let flashcard(x, y, content, footer: none) = {
          place(
            top + left,
            dx: x,
            dy: y,
            rect(
              width: card_width,
              height: card_height,
              stroke: none,
              fill: none,
              box(
                width: card_width - 0.2in,
                height: card_height - 0.2in,
                inset: 0.1in,
                [
                  #align(center + horizon, content)
                  #if footer != none {
                    place(
                      bottom + right,
                      text(size: 10pt, footer)
                    )
                  }
                ]
              )
            )
          )
        }

        // Generate pages for card fronts
        #let total_cards = flashcards.len()
        #let total_pages = calc.ceil(total_cards / cards_per_page)

        #for page_num in range(0, total_pages) {
          // Generate card fronts
          page(width: 8.5in, height: 11in, margin: (x: 0.75in, y: 0.5in))[
            #align(center + horizon)[
              #block(
                width: columns * card_width,
                height: rows * card_height,
              )[
                #for row in range(0, rows) {
                  for col in range(0, columns) {
                    let i = page_num * cards_per_page + row * columns + col
                    if i < flashcards.len() {
                      let x = col * card_width
                      let y = row * card_height
                      let content = text(size: 14pt, weight: "bold", flashcards.at(i).question)
                      flashcard(x, y, content)
                    }
                  }
                }
              ]
            ]
          ]

          // Generate card backs (columns mirrored for duplex printing)
          page(width: 8.5in, height: 11in, margin: (x: 0.75in, y: 0.5in))[
            #align(center + horizon)[
              #block(
                width: columns * card_width,
                height: rows * card_height,
              )[
                #for row in range(0, rows) {
                  for col in range(0, columns) {
                    let i = page_num * cards_per_page + row * columns + col
                    if i < flashcards.len() {
                      let x = (columns - col - 1) * card_width
                      let y = row * card_height
                      let content = stack(
                        dir: ttb,
                        spacing: 0.2in,
                        text(size: 16pt, weight: "bold", flashcards.at(i).answer),
                        text(size: 11pt, flashcards.at(i).note)
                      )
                      flashcard(x, y, content, footer: flashcards.at(i).footer)
                    }
                  }
                }
              ]
            ]
          ]
        }
        """.trimIndent()
    )
}
