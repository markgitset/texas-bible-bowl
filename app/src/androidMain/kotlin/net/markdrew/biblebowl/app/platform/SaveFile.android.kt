package net.markdrew.biblebowl.app.platform

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.markdrew.biblebowl.app.AppContext
import java.io.File

/**
 * Saves the file to the device's Downloads.
 *
 * On Android 10+ (API 29, scoped storage) this goes through MediaStore — no storage permission needed and
 * the file lands in the shared Downloads collection. On older devices it falls back to the app's own
 * external files dir, which also needs no permission (just not shared with other apps).
 */
actual suspend fun saveFile(fileName: String, bytes: ByteArray, mimeType: String): String = withContext(Dispatchers.IO) {
    val context = AppContext.app
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, pending)
            ?: return@withContext "Could not create Downloads/$fileName"
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: return@withContext "Could not open Downloads/$fileName for writing"
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        "Saved to Downloads/$fileName"
    } else {
        // Pre-Q fallback: app-scoped external files dir (no runtime permission required).
        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
            .also { it.mkdirs() }
        File(dir, fileName).writeBytes(bytes)
        "Saved to ${File(dir, fileName).absolutePath}"
    }
}
