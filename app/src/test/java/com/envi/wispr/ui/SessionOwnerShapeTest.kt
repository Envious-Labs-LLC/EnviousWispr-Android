package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    /**
     * Drift Guard (#115): the owner never blocks and never polls. No `runBlocking`, no thread of its own,
     * no sleep in the coordinator; no `runBlocking` or join in the three teardowns (the owner's `destroy`,
     * the paste service's and the audio service's `onDestroy`). A blocking wait that comes back here is
     * the hang this change removed, wherever it is placed. REVERT: restore any one of them.
     */
    @Test
    fun theOwnerNeverBlocksAndNeverPolls() {
        // Since #216 the owner is three files; a blocking wait or a second thread is refused in any of them.
        val owner = SessionSources.all
        listOf("runBlocking", "Thread.sleep", "startPolling", "waitForFileReady", "Thread.join", ".join(").forEach {
            assertFalse("the session owner must not contain $it", owner.contains(it))
        }
        // The ONE thread the owner makes is the capture command lane's, inside its executor's factory in
        // `CaptureSessionController`; no other `Thread(` may appear (the old live waiter, poller, transcribe
        // and cleanup threads).
        assertEquals("one Thread( in the session owner, the lane's", 1, Regex("""(^|[^A-Za-z0-9_.])Thread\(""").findAll(owner).count())
        assertTrue(SessionSources.capture.contains("Thread(runnable, \"CaptureCommands\")"))
        val pasteService = File("src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt").readText()
        check(pasteService.contains("override fun onDestroy()"))
        val paste = pasteService.substringAfter("override fun onDestroy()")
        // Since #217 onDestroy hands its teardown to three collaborators' `close`; a wait there is the same
        // hang, so their bodies are scanned too.
        val closes = listOf("EditorTargetTracker.kt", "AccessibilityInsertionRunner.kt", "AccessibilityBubbleHost.kt").map { name ->
            val text = File("src/main/java/com/envi/wispr/paste/$name").readText()
            val from = text.indexOf("fun close() {")
            check(from >= 0) { "$name must declare its close" }
            val to = text.indexOf("\n    }\n", from)
            check(to > from) { "$name's close must end" }
            text.substring(from, to)
        }
        (listOf(paste) + closes).forEach { teardown ->
            listOf("runBlocking", "joinAll", ".join(").forEach { assertFalse("the paste service's teardown must not contain $it", teardown.contains(it)) }
            assertFalse("and never queued behind a History write", teardown.contains("enqueue(\"clean-stop marker\")"))
        }
        assertTrue("the clean-stop marker is written in onDestroy itself, last", paste.substringBefore("super.onDestroy()").trimEnd().endsWith("markStopWasClean()"))
        val audio = File("src/main/java/com/envi/wispr/audio/AudioCaptureService.kt").readText().substringAfter("override fun onDestroy()")
        listOf("runBlocking", ".join(", "Thread.sleep").forEach { assertFalse("the audio service's onDestroy must not contain $it", audio.contains(it)) }
    }

    /**
     * Drift Guard (#216): the owner decides and delegates. [CaptureSessionController] talks to the capture
     * process and [SessionFinalizer] writes the row and delivers the words; neither can see the state or
     * end a take, and the owner holds none of their mechanics. Read as CODE through [codeOnly], so a
     * comment or a string can neither satisfy nor break a row. When it fails, the user sees nothing at
     * once; an edit is putting the take's decisions into a class that cannot see the state machine, or
     * the transport back into the owner.
     * REVERT: move the draft insert's `historyWrites.enqueue` back into `publishLive`; add a second
     * `Executors.newSingleThreadExecutor` to the controller; give `SessionFinalizer.deliver` a
     * `TerminalReason` parameter; add a `CaptureEvent` member carrying a `TerminalReason`; make
     * `TakeContext.targetPin` a `var`.
     */
    @Test
    fun theOwnerDecidesAndItsTwoCollaboratorsOnlyReport() {
        val dir = "src/main/java/com/envi/wispr/ui"
        val files = listOf("DictationSessionCoordinator.kt", "CaptureSessionController.kt", "SessionFinalizer.kt", "TakeContext.kt").map { File("$dir/$it") }
        val code = codeOnly(files).mapKeys { it.key.name }
        val owner = code.getValue("DictationSessionCoordinator.kt")
        val controller = code.getValue("CaptureSessionController.kt")
        val finalizer = code.getValue("SessionFinalizer.kt")
        val context = code.getValue("TakeContext.kt")
        fun count(text: String, pattern: String) = Regex(pattern).findAll(text).count()

        // (a) The owner holds none of the mechanics it delegates.
        listOf(
            """historyWrites\??\.enqueue\(""", """\bExecutors\.""", """\bpostToMainDelayed\(""", """\blistenForTake\(""",
            """\bstartCaptureForTake\(""", """\bpasteWhenTargetReturns\(""", """\bcopyToClipboard\(""", """\bInsertionJudgement\.""",
            """\bpipeline\.capture\b""", """\bCaptureLink\b""",
        ).forEach { assertEquals("the owner's code holds no $it", 0, count(owner, it)) }

        // (b) Each mechanic has exactly the one home the split gave it.
        assertEquals("one lane", 1, count(controller, """\bExecutors\.newSingleThreadExecutor\b"""))
        assertEquals("one listener registration", 1, count(controller, """\blistenForTake\("""))
        assertEquals("one start call", 1, count(controller, """\bstartCaptureForTake\("""))
        assertEquals("three timer posts: the bound's arm and re-arm, the live deadline", 3, count(controller, """\bpostToMainDelayed\("""))
        assertEquals(
            // #235 adds two: a timed-out take's copy outcome, and the promotion to ready after a scheduled handoff.
            "the nine History writes: draft insert, status, discard, interrupted, finalize, clipboard, history only, timed-out copy outcome, promote to ready",
            9,
            count(finalizer, """historyWrites\??\.enqueue\("""),
        )
        assertEquals("one handoff", 1, count(finalizer, """\bpasteWhenTargetReturns\("""))
        assertEquals("one clipboard write", 1, count(finalizer, """\bcopyToClipboard\("""))

        // (c) Neither collaborator can see the state or decide an ending.
        mapOf("CaptureSessionController.kt" to controller, "SessionFinalizer.kt" to finalizer).forEach { (name, text) ->
            listOf(
                """\bSessionState\b""", """\bTerminalReason\b""", """\bTakeArbiter\b""", """\bTakeContext\b""", """\barbiter\b""",
                """\.reserve\(""", """\.commit\(""", """\.commitNow\(""", """\.interrupt\(""",
            ).forEach { assertEquals("$name holds no $it", 0, count(text, it)) }
        }

        // (d) The capture side reports exactly these facts.
        val members = Regex("""\b(?:data class|data object|class|object)\s+(\w+)[^\n{]*:\s*CaptureEvent\b""").findAll(controller).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("Live", "Tick", "SilenceStatus", "Ended", "Silent", "LiveDeadlinePassed", "StartFailed"), members)

        // (e) The take is fixed once built: every constructor property a `val`, no `var` anywhere.
        val parameters = context.substringAfter("class TakeContext(").substringBefore(") {")
        val declared = parameters.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("six properties", 6, declared.size)
        declared.forEach { assertTrue("a fixed property: $it", it.startsWith("val ")) }
        assertEquals("no var in TakeContext", 0, count(context, """\bvar\b"""))
    }

    @Test
    fun serviceLineCountIsReported() {
        // A metric for the reader of the test output, not a threshold: 1,937 lines before #186.
        println("DictationSessionService.kt: ${service.lines().size} lines")
    }
}
