package net.markdrew.biblebowl.app.platform

/** MIME types for the file kinds the app generates. */
object Mime {
    const val PDF = "application/pdf"
    const val TSV = "text/tab-separated-values"
    const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}

/**
 * Delivers a generated file to the user in the platform-idiomatic way:
 * browser download (web), Downloads folder (Android), or the user's Downloads/home (desktop).
 *
 * Returns a short human-readable description of where the file went (shown in a snackbar/message).
 */
expect suspend fun saveFile(fileName: String, bytes: ByteArray, mimeType: String = Mime.PDF): String
