package net.markdrew.biblebowl.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

actual suspend fun savePdf(fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val downloads: Path = Paths.get(System.getProperty("user.home"), "Downloads")
        .takeIf(Files::isDirectory) ?: Paths.get(System.getProperty("user.home"))
    val target = downloads.resolve(fileName)
    Files.write(target, bytes)
    "Saved to $target"
}
