package net.markdrew.biblebowl.app.platform

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun savePdf(fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    downloads.mkdirs()
    val target = File(downloads, fileName)
    target.writeBytes(bytes)
    "Saved to Downloads/$fileName"
}
