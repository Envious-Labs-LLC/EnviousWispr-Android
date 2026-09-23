package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `scripts/check-visibility.py --code-only`, one process for every file, keyed back by path: each file's
 * text with every comment and string blanked, the one lexer owner the Drift Guards read code through
 * (`SessionOwnerShapeTest` pins it with a contract row; `PasteServiceShapeTest` reads through it, #217).
 */
internal fun codeOnly(files: List<File>): Map<File, String> {
    val script = File("../scripts/check-visibility.py").canonicalFile
    assertTrue("the check must exist at ${script.path}", script.isFile)
    val process = ProcessBuilder(listOf("python3", script.path, "--code-only") + files.map { it.path })
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().readText()
    assertTrue("the check must finish", process.waitFor(120, TimeUnit.SECONDS))
    assertEquals("the check must answer:\n$out", 0, process.exitValue())
    // The stream is `=== <path>\n`, the answer, then one newline that is the answer's own or one the
    // service adds, per file in order. The answer has exactly the file's LENGTH in CODE POINTS (the
    // service blanks, never deletes, and counts as Python does; Kotlin's `length` is UTF-16 units and
    // an emoji in a comment is one point but two units), so the reader walks by code points and
    // never splits on newlines: rounds 6 and 7
    // each found a newline shape a split-and-join reader rebuilt wrongly (no final newline; a
    // blank-only file), and the class is closed here rather than patched a third time. A service
    // that changed a length would land the next header check on the wrong bytes, loudly.
    val result = LinkedHashMap<File, String>()
    var pos = 0
    for (file in files) {
        val header = "=== ${file.path}\n"
        assertEquals("answer ${result.size + 1} of ${files.size} is for ${file.path}", header, out.substring(pos, minOf(out.length, pos + header.length)))
        pos += header.length
        val text = file.readText()
        val points = text.codePointCount(0, text.length)
        assertTrue("the answer for ${file.path} is complete", out.codePointCount(pos, out.length) >= points)
        val end = out.offsetByCodePoints(pos, points)
        val answer = out.substring(pos, end)
        pos = end
        if (!text.endsWith("\n")) {
            assertEquals("the service ends an unterminated answer with one newline", "\n", out.substring(pos, minOf(out.length, pos + 1)))
            pos += 1
        }
        result[file] = answer
    }
    assertEquals("nothing after the last answer", out.length, pos)
    assertEquals("every file answered", files.size, result.size)
    return result
}
