package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drift Guard (#186): the Service is the Android shell and nothing else. A state machine cannot exist
 * without a lock, a compare-and-set, an arbiter, a ledger or a terminal reason, so their absence from the
 * Service source is the property; the field set pins what the shell is allowed to hold. A token check on
 * one name alone is evaded by a rename, and a line ceiling fails on comments (Codex review G1, 2026-09-20),
 * so the line count below is REPORTED, never gated.
 *
 * REVERT: paste one `state.compareAndSet` or one `synchronized(` back into the Service, or add a field.
 */
class SessionOwnerShapeTest {
    private val service = File("src/main/java/com/envi/wispr/ui/DictationSessionService.kt").readText()

    @Test
    fun serviceOwnsOnlyItsAdapters() {
        // Instance fields, read off the source: every member declared at class indentation with val/var.
        // Any run of annotations (with or without arguments and spaces) and any modifiers before val/var,
        // so `@Volatile private var` and `@Suppress("x y") private val` are enumerated too (Codex reviews
        // C1 and C2, 2026-09-20).
        val fields = Regex("""(?m)^ {4}(?:(?:@[^\r\n]*?)\s+|(?:[A-Za-z_]\w*)\s+)*(?:val|var)\s+(\w+)\b""")
            .findAll(service).map { it.groupValues[1] }.toSet()
        assertEquals(
            "the Service holds exactly its adapters; a take's state lives in the coordinator",
            setOf("mainHandler", "languageDetector", "preferences", "bindings", "coordinator"),
            fields,
        )
        listOf("synchronized(", "compareAndSet(", "TakeArbiter", "PolishRequestLedger", "TerminalReason", "SessionState").forEach { token ->
            assertFalse("the Service source carries '$token', which only a state machine needs", service.contains(token))
        }
    }

    /**
     * Drift Guard (#192): the session owner is the only component that pins the field a take aims at,
     * and it pins once, at admission. The launcher and the bubble's direct start each pinned too, so a
     * TOGGLE that STOPPED a take re-pinned the field the user had moved to. The property is the whole
     * inventory of pin CALLS across production source (Codex code review round 1, 2026-09-21: a check on
     * three named regions stays green when a fourth file reaches the pin through a helper): the owner's
     * one call inside `beginSession`, the gateway's delegation, and the companion's call into the
     * private pin. Any other file, or a second call in these, fails.
     *
     * Calls are read from CODE only, through [codeOnly], which is `scripts/check-visibility.py` run as a
     * service: rounds 2 to 5 each found a comment-or-string shape a local text reader misread (a KDoc
     * naming the call; a block-comment opener inside a string swallowing the code after it; a `//`
     * inside a URL string truncating the line; a port that kept delimiters its owner masks), so the
     * lexical states have ONE owner, the shipped check, and this row carries none.
     * REVERT: restore `PasteAccessibilityService.pinTargetForDictation()` in the launcher, or
     * `pinTarget()` in `startDictationFromBubble`; receipts R5 and R6 do so behind a block-comment-opener
     * string and a `//` string and the row stays red.
     */
    @Test
    fun onlyTheOwnerPinsTheTarget() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val callSites = codeOnly(sources).flatMap { (file, code) ->
            code.lines().mapIndexedNotNull { index, line ->
                // A declaration is not a call; the gateway declares AND calls on one line, so the
                // declaration is cut out and whatever call remains counts.
                val rest = line.replace(Regex("""\bfun\s+pinTarget(ForDictation)?\([^)]*\)"""), "")
                val isCall = rest.contains("pinTargetForDictation(") || rest.contains("pinTarget(")
                if (isCall) "${file.name}:${index + 1}" else null
            }
        }
        assertEquals(
            "the owner, the gateway and the companion are the only pin callers; the line numbers move, the file set does not",
            listOf("DictationSessionCoordinator.kt", "InsertionGateway.kt", "PasteAccessibilityService.kt"),
            callSites.map { it.substringBefore(":") }.sorted(),
        )

        val coordinator = codeOnly(sources.filter { it.name == "DictationSessionCoordinator.kt" }).values.single()
        val beginSession = coordinator.substring(coordinator.indexOf("private fun beginSession("))
            .let { it.substring(0, it.indexOf("\n    private fun ")) }
        assertTrue("the owner's one call is inside beginSession", beginSession.contains(".pinTargetForDictation()"))
    }

    /**
     * Contract row on the service this class reads through: each Kotlin lexical state blanked to spaces
     * INCLUDING its delimiters (the owner's convention), code kept, newlines kept so line numbers hold.
     * Expectations are literals built from `" ".repeat(n)`, never from the service.
     * REVERT: in `scripts/check-visibility.py` `code_mask`, mask the `"` that opens a string as code; the
     * string rows fail.
     */
    @Test
    fun theCodeOnlyServiceBlanksEveryNonCodeState() {
        val blank = { n: Int -> " ".repeat(n) }
        val shapes = listOf(
            "val a = 1 // pin(" to "val a = 1 " + blank(7),
            "val b = /* pin( */ 2" to "val b = " + blank(10) + " 2",
            "val c = /* a /* pin( */ b */ 3" to "val c = " + blank(20) + " 3",
            "val d = \"https://x/pin( /*\"" to "val d = " + blank(19),
            "val e = \"a\\\"b\" + f" to "val e = " + blank(6) + " + f",
            "val g = \"\"\"// pin(\"\"\"" to "val g = " + blank(13),
            "val h = '\\''" to "val h = " + blank(4),
            "val i = \"a \${pin()} b\"" to "val i = " + blank(5) + "pin()" + blank(4),
            "// pin\nval j = 1" to blank(6) + "\nval j = 1",
            // An emoji is one code point and two UTF-16 units; the service blanks it to ONE space and the
            // reader walks by code points (two production files hold one: DeterministicCleanup.kt, OnboardingDemo.kt).
            "// \uD83C\uDF99\nval k = 1" to blank(4) + "\nval k = 1",
        )
        // Five files in one call: the shapes terminated, the shapes unterminated (one production file
        // ends without a newline, Codex code review round 6), a blank-only file of two newlines (round 7),
        // an empty file, and a CRLF file (round 8); each answer must come back byte for byte and in order.
        val expected = shapes.joinToString("\n") { it.second }
        val fixtures = listOf(
            shapes.joinToString("\n") { it.first } + "\n" to expected + "\n",
            shapes.joinToString("\n") { it.first } to expected,
            "\n\n" to "\n\n",
            "" to "",
            // CRLF kept as two characters on both sides (round 8; no production file uses CRLF today). The
            // first CR sits inside the line comment, which runs to the LF, so it is blanked like any comment
            // character; the second is code and stays.
            "val l = 1 // pin(\r\nval m = 2\r\n" to "val l = 1 " + blank(8) + "\nval m = 2\r\n",
        ).map { (text, want) -> File.createTempFile("code-only", ".kt").apply { writeText(text) } to want }
        try {
            val answers = codeOnly(fixtures.map { it.first })
            fixtures.forEach { (file, want) -> assertEquals(file.readText().take(20), want, answers.getValue(file)) }
        } finally {
            fixtures.forEach { it.first.delete() }
        }
    }

    /** `scripts/check-visibility.py --code-only`, one process for every file, keyed back by path. */
    private fun codeOnly(files: List<File>): Map<File, String> {
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

    /**
     * Drift Guard (#115): the owner never blocks and never polls. No `runBlocking`, no thread of its own,
     * no sleep in the coordinator; no `runBlocking` or join in the three teardowns (the owner's `destroy`,
     * the paste service's and the audio service's `onDestroy`). A blocking wait that comes back here is
     * the hang this change removed, wherever it is placed. REVERT: restore any one of them.
     */
    @Test
    fun theOwnerNeverBlocksAndNeverPolls() {
        val coordinator = File("src/main/java/com/envi/wispr/ui/DictationSessionCoordinator.kt").readText()
        listOf("runBlocking", "Thread.sleep", "startPolling", "waitForFileReady", "Thread.join", ".join(").forEach {
            assertFalse("the coordinator must not contain $it", coordinator.contains(it))
        }
        // The ONE thread the owner makes is the capture command lane's, inside its executor's factory;
        // no other `Thread(` may appear (the old live waiter, poller, transcribe and cleanup threads).
        assertEquals("one Thread( in the coordinator, the lane's", 1, Regex("""(^|[^A-Za-z0-9_.])Thread\(""").findAll(coordinator).count())
        assertTrue(coordinator.contains("Thread(runnable, \"CaptureCommands\")"))
        val paste = File("src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt").readText().substringAfter("override fun onDestroy()")
        listOf("runBlocking", "joinAll", ".join(").forEach { assertFalse("the paste service's onDestroy must not contain $it", paste.contains(it)) }
        assertTrue("the clean-stop marker is queued last, behind the outcome write", paste.contains("enqueue(\"clean-stop marker\") { markStopWasClean() }"))
        val audio = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText().substringAfter("override fun onDestroy()")
        listOf("runBlocking", ".join(", "Thread.sleep").forEach { assertFalse("the audio service's onDestroy must not contain $it", audio.contains(it)) }
    }

    @Test
    fun serviceLineCountIsReported() {
        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
        println("DictationSessionService.kt: ${service.lines().size} lines")
    }
}
