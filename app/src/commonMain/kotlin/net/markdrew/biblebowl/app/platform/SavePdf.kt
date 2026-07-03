package net.markdrew.biblebowl.app.platform

/**
 * Delivers a generated PDF to the user in the platform-idiomatic way:
 * browser download (web), Downloads folder (Android), or a file next to the user's home (desktop).
 *
 * Returns a short human-readable description of where the file went (shown in a snackbar/message).
 */
expect suspend fun savePdf(fileName: String, bytes: ByteArray): String
