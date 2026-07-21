package net.markdrew.biblebowl.web

import kotlinx.browser.document
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

/** Quote-escapes a CSV field, guarding user-entered text against spreadsheet formula injection. */
fun csvField(raw: String): String {
    val guarded = if (raw.firstOrNull() in listOf('=', '+', '-', '@')) "'$raw" else raw
    return "\"" + guarded.replace("\"", "\"\"") + "\""
}

/** Joins pre-escaped lines of fields into CSV text (CRLF rows, every field [csvField]-quoted). */
fun csvText(lines: List<List<String>>): String =
    lines.joinToString("\r\n") { line -> line.joinToString(",") { csvField(it) } }

fun downloadCsv(fileName: String, csv: String) {
    // BOM so Excel detects UTF-8.
    val blob = Blob(arrayOf<dynamic>("\uFEFF$csv"), BlobPropertyBag(type = "text/csv;charset=utf-8"))
    val url = URL.createObjectURL(blob)
    val a = document.createElement("a") as HTMLAnchorElement
    a.href = url
    a.download = fileName
    document.body?.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
}
