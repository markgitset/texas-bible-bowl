package net.markdrew.biblebowl.server.typst

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
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
     * Compiles [typstSource] and returns the PDF bytes.
     *
     * @throws TypstException if typst is unavailable, times out, or reports errors
     */
    fun compile(typstSource: String, timeoutSeconds: Long = 60): ByteArray {
        if (!isAvailable) throw TypstException("typst binary not found on PATH")
        val dir: Path = Files.createTempDirectory("tbb-typst")
        val typFile = dir.resolve("doc.typ").apply { writeText(typstSource) }
        val pdfFile = dir.resolve("doc.pdf")
        try {
            val process = ProcessBuilder(
                "typst", "compile",
                typFile.absolutePathString(),
                pdfFile.absolutePathString(),
            ).redirectErrorStream(true).start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw TypstException("typst compile timed out after ${timeoutSeconds}s")
            }
            if (process.exitValue() != 0) {
                val output = process.inputStream.bufferedReader().readText().take(2000)
                throw TypstException("typst compile failed (exit ${process.exitValue()}): $output")
            }
            return pdfFile.readBytes()
        } finally {
            typFile.deleteIfExists()
            pdfFile.deleteIfExists()
            dir.deleteIfExists()
        }
    }
}
