package net.markdrew.biblebowl.server.typst

import net.markdrew.biblebowl.generate.stampLayoutRevision
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Thrown when the `typst` binary is missing or compilation fails. */
class TypstException(message: String) : Exception(message)

/**
 * Compiles Typst markup to PDF bytes by shelling out to the `typst` CLI (bundled in the server's
 * container image; see bible-bowl's typst.kt for the original Path-based variant).
 */
object TypstCompiler {

    val isAvailable: Boolean by lazy {
        runCatching {
            ProcessBuilder("typst", "--version").start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    /**
     * Compiles [typstSource] and returns the PDF bytes. Each entry in [assets] (filename → bytes) is written
     * into the compile directory next to the source, so the markup can reference it by name (e.g.
     * `#image("tbb-logo.png")`) — `typst` sees a lone string otherwise, with no bundled files.
     *
     * [layoutRevision] is the generator's [net.markdrew.biblebowl.generate.LayoutRevisions] entry,
     * stamped in tiny gray type in the bottom-right corner of the document's last page so any copy
     * names the layout that drew it. It is deliberately not defaulted: requiring it here — the one
     * place every PDF passes through — is what keeps a new endpoint from shipping an undatable
     * document.
     *
     * @throws TypstException if typst is unavailable, times out, or reports errors
     */
    fun compile(
        typstSource: String,
        layoutRevision: Int,
        timeoutSeconds: Long = 60,
        assets: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        if (!isAvailable) throw TypstException("typst binary not found on PATH")
        val dir: Path = Files.createTempDirectory("tbb-typst")
        val typFile = dir.resolve("doc.typ").apply { writeText(stampLayoutRevision(typstSource, layoutRevision)) }
        val pdfFile = dir.resolve("doc.pdf")
        val assetFiles = assets.map { (name, bytes) -> dir.resolve(name).apply { writeBytes(bytes) } }
        try {
            val process = ProcessBuilder(
                "typst", "compile",
                typFile.absolutePathString(),
                pdfFile.absolutePathString(),
            ).redirectErrorStream(true).start()

            // Drain the merged stdout/stderr on a separate thread while typst runs. A big compile can emit
            // thousands of warnings (e.g. an unknown font → one per glyph run); if we only read the pipe
            // after waitFor, its ~64KB OS buffer fills, typst blocks on write, and we deadlock to the
            // timeout. Draining concurrently keeps typst unblocked and preserves the output for errors.
            val output = StringBuilder()
            val drainer = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { if (output.length < 4000) output.appendLine(line) }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw TypstException("typst compile timed out after ${timeoutSeconds}s")
            }
            drainer.join(5_000)
            if (process.exitValue() != 0) {
                val tail = synchronized(output) { output.toString() }.take(2000)
                throw TypstException("typst compile failed (exit ${process.exitValue()}): $tail")
            }
            return pdfFile.readBytes()
        } finally {
            typFile.deleteIfExists()
            pdfFile.deleteIfExists()
            assetFiles.forEach { it.deleteIfExists() }
            dir.deleteIfExists()
        }
    }
}
