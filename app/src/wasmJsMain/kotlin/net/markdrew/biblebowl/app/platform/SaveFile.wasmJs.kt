package net.markdrew.biblebowl.app.platform

import kotlinx.browser.document
import org.khronos.webgl.Int8Array
import org.khronos.webgl.set
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

actual suspend fun saveFile(fileName: String, bytes: ByteArray, mimeType: String): String {
    val array = Int8Array(bytes.size)
    bytes.forEachIndexed { i, b -> array[i] = b }
    val blob = Blob(
        blobParts = arrayOf<JsAny?>(array.buffer).toJsArray(),
        options = BlobPropertyBag(type = mimeType),
    )
    val url = URL.createObjectURL(blob)
    val anchor = document.createElement("a") as HTMLAnchorElement
    anchor.href = url
    anchor.download = fileName
    anchor.click()
    URL.revokeObjectURL(url)
    return "Downloaded $fileName"
}

private fun Array<JsAny?>.toJsArray(): JsArray<JsAny?> {
    val out = JsArray<JsAny?>()
    forEachIndexed { i, v -> out[i] = v }
    return out
}
