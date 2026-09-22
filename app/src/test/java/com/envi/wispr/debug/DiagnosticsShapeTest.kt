package com.envi.wispr.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#194, `kotlin-patterns.md` RULE: no-content-in-diagnostics): the app's diagnostics are
 * content-free by SHAPE, read off the source of `app/src/main`, `app/src/debug` and `app/src/androidTest`
 * plus the llama.cpp bridge:
 *
 * (a) `android.util.Log` is imported or named by exactly one file, `debug/DebugLogger.kt`, so every line
 *     reaches logcat through [DebugLogger.render]'s rendering of a throwable;
 * (b) nothing anywhere prints a stack (`printStackTrace`, `stackTraceToString`, the platform's
 *     stack-string helper), reads a localized message, or reaches into a cause's message; the throwable's
 *     stack-trace array is read in ONE place, the renderer;
 * (c) no diagnostic call span (a `DebugLogger.*`, `SessionLog` `log.*`, `logInfo`/`logWarn`, or the
 *     speech process's `failure.report(`) carries `.message`, a throwable's `toString()`, a template of a
 *     conventionally named throwable, or a `format` whose value argument is one;
 * (d) the renderer itself hands no throwable to a three-argument `Log.e`/`Log.w` and has no file sink;
 * (e) the four content-bearing sources the change reduced stay reduced: the debug insert receiver and
 *     the silence device test log a LENGTH, the delivery worker logs the host's closed token, and the
 *     llama.cpp log callback prints nothing.
 *
 * The scan reads RAW text, comments included: a comment that needs a forbidden token writes around it, as
 * the plan's citation checker already makes prose do. The throwable-in-template check (c) is name-based,
 * not type-aware: a throwable bound to an unconventional name and templated bare (`"$oops"`) passes; a
 * type-aware rule is a compiler plugin away and out of this change's budget, so the reviewer's eye and
 * [DebugLoggerRenderTest] are the guard for that shape.
 *
 * Parentheses are balanced naively, string literals included: an unbalanced parenthesis inside a string
 * extends a span or a comma count, which errs LOUD (a false red names its line), never silent.
 *
 * Every row has a two-way control: the POSITIVE fixture holds one instance of every forbidden shape and the
 * scan must name each; the ALLOWED fixture holds the shapes that look alike and are fine, and the scan must
 * name none. A scan whose fixtures were not exercised is a scan whose silence means nothing.
 */
class DiagnosticsShapeTest {

    private val roots = listOf("src/main/java", "src/debug/java", "src/androidTest/java")
    private val owner = "src/main/java/com/envi/wispr/debug/DebugLogger.kt"
    private val sources: List<File> =
        roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test
    fun theTreeIsScanned() {
        assertTrue("main, debug and androidTest sources must be found from the module directory", sources.size > 100)
        assertTrue("the owner must be one of them", sources.any { it.path == owner })
    }

    /** REVERT: add `import android.util.Log` to any other file, or call `android.util.Log.i(` inline. */
    @Test
    fun onlyTheDiagnosticOwnerImportsAndroidLog() {
        val offenders = sources.filter { it.path != owner }.flatMap { file -> androidLogUses(file.path, file.readText()) }
        assertEquals("every logcat line goes through DebugLogger", emptyList<String>(), offenders)
        assertTrue(File(owner).readText().contains("import android.util.Log\n"))
    }

    /** REVERT: `e.printStackTrace()`, `e.stackTraceToString()`, `e.localizedMessage`, `e.cause?.message` anywhere. */
    @Test
    fun noFileRendersAStackOrAMessageChain() {
        val offenders = sources.flatMap { file -> stackOrChainUses(file.path, file.readText()) }
        assertEquals("a stack carries every frame's locals in its message", emptyList<String>(), offenders)
        val stackReads = sources.filter { it.path != owner }.flatMap { file -> stackTraceReads(file.path, file.readText()) }
        assertEquals("only the renderer reads a stack-trace array", emptyList<String>(), stackReads)
        assertEquals("the renderer reads it exactly once", 1, stackTraceReads(owner, File(owner).readText()).size)
    }

    /** REVERT: reintroduce one `${e.message}`, or `e.message.orEmpty()` at `AsrService.kt:133`. */
    @Test
    fun noDiagnosticLineCarriesExceptionText() {
        val offenders = sources.flatMap { file -> exceptionTextInDiagnosticSpans(file.path, file.readText()) }
        assertEquals("a diagnostic line describes shape, never exception text", emptyList<String>(), offenders)
    }

    /** REVERT: `Log.e(tag, message, throwable)` in the owner, or the shared-storage sink back. */
    @Test
    fun theOwnerNeverHandsAThrowableToAndroidLog() {
        val text = File(owner).readText()
        assertEquals("no three-argument Log.e/Log.w", emptyList<String>(), threeArgumentLogCalls(owner, text))
        listOf("stackTraceToString", "Environment", "FileWriter").forEach { token ->
            assertTrue("the owner carries '$token'", !text.contains(token))
        }
    }

    /** REVERT: log `$host`, `$text`, or restore the callback's print. */
    @Test
    fun specialContentSourcesStayReducedToShape() {
        val insert = File("src/debug/java/com/envi/wispr/debug/DebugInsertReceiver.kt").readText()
        val insertSpans = diagnosticSpans(insert)
        assertTrue("the insert receiver logs", insertSpans.isNotEmpty())
        assertTrue("the insert receiver logs the length", insertSpans.any { it.contains("textChars=\${text.length}") })
        insertSpans.forEach { span -> assertTrue(span, !Regex("""\${'$'}text\b|\${'$'}\{text\}""").containsMatchIn(span)) }

        val silence = File("src/androidTest/java/com/envi/wispr/SilenceStoppedTakeTranscribesDeviceTest.kt").readText()
        val silenceSpans = diagnosticSpans(silence)
        assertTrue("the silence test logs", silenceSpans.isNotEmpty())
        assertTrue("the silence test logs the length", silenceSpans.any { it.contains("chars=\${text.length}") })
        silenceSpans.forEach { span -> assertTrue(span, !Regex("""\${'$'}text\b|\${'$'}\{text\}""").containsMatchIn(span)) }

        val worker = File("src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt").readText()
        val sourceLine = worker.lines().single { it.contains("\"Model source: ") }
        assertTrue(sourceLine, sourceLine.contains("ModelSourceHost.of(host).wire"))
        assertTrue(sourceLine, !Regex("""\${'$'}host\b|\${'$'}\{host\}""").containsMatchIn(sourceLine))

        val jni = File("../llama-android/src/main/cpp/s1_jni.cpp").readText()
        val callback = Regex("""void llama_log_callback\([^)]*\)\s*\{([^}]*)\}""").find(jni)
        assertTrue("llama_log_callback must exist", callback != null)
        assertTrue("the callback prints nothing", !callback!!.groupValues[1].contains("__android_log_print"))
        val prints = Regex("""__android_log_print""").findAll(jni).count()
        assertEquals("the one print in the bridge is log_info's", 1, prints)
        assertTrue(Regex("""void log_info\([^)]*\)\s*\{[^}]*__android_log_print""").containsMatchIn(jni))
    }

    // ---- the two-way control -------------------------------------------------------------------------

    @Test
    fun theScanNamesEveryForbiddenShapeInThePositiveFixture() {
        val found = androidLogUses("Fixture.kt", POSITIVE) + stackOrChainUses("Fixture.kt", POSITIVE) +
            stackTraceReads("Fixture.kt", POSITIVE) + exceptionTextInDiagnosticSpans("Fixture.kt", POSITIVE) +
            threeArgumentLogCalls("Fixture.kt", POSITIVE)
        val expected = listOf(
            "import android.util.Log", "android.util.Log.", "getStackTraceString", "printStackTrace", ".stackTrace",
            "stackTraceToString", ".localizedMessage", ".cause?.message", ".cause!!.message",
            "span:.message", "span:toString", "span:format",
            "span:\$e", "span:\$error", "span:\$it", "span:\$throwable", "span:\$failure", "span:\$exception",
            "span:\$cause", "span:\$t",
            "span:\${e}", "span:\${error}", "span:\${it}", "span:\${throwable}", "span:\${failure}", "span:\${exception}",
            "span:\${cause}", "span:\${t}",
            "Log.e:3", "Log.w:3",
        )
        val missing = expected.filter { shape -> found.none { it.endsWith(" $shape") } }
        assertEquals("the scan must name every planted shape; found=$found", emptyList<String>(), missing)
    }

    @Test
    fun theScanNamesNothingInTheAllowedFixture() {
        val found = androidLogUses("Allowed.kt", ALLOWED) + stackOrChainUses("Allowed.kt", ALLOWED) +
            stackTraceReads("Allowed.kt", ALLOWED) + exceptionTextInDiagnosticSpans("Allowed.kt", ALLOWED) +
            threeArgumentLogCalls("Allowed.kt", ALLOWED)
        assertEquals(emptyList<String>(), found)
    }

    // ---- the scan --------------------------------------------------------------------------------------

    private fun androidLogUses(path: String, text: String): List<String> = buildList {
        if (text.contains("import android.util.Log\n") || text.contains("import android.util.Log\r")) add("$path import android.util.Log")
        if (text.contains("android.util.Log.")) add("$path android.util.Log.")
    }

    private fun stackOrChainUses(path: String, text: String): List<String> = buildList {
        listOf("getStackTraceString", "printStackTrace", "stackTraceToString", ".localizedMessage", ".cause?.message", ".cause!!.message")
            .forEach { token -> if (text.contains(token)) add("$path $token") }
    }

    /** `.stackTrace` as a property read; `stackTraceToString` is (b)'s and `fillInStackTrace` is a different word. */
    private fun stackTraceReads(path: String, text: String): List<String> =
        Regex("""\.stackTrace\b""").findAll(text).map { "$path .stackTrace" }.toList()

    private val diagnosticCallTokens = listOf(
        "DebugLogger.log(", "DebugLogger.warn(", "DebugLogger.error(", "DebugLogger.debug(",
        "log.log(", "log.warn(", "log.error(", "logInfo(", "logWarn(", "failure.report(",
    )

    /** The balanced-paren argument text of every diagnostic call; a multi-line call is one span. */
    private fun diagnosticSpans(text: String): List<String> = buildList {
        for (token in diagnosticCallTokens) {
            var from = text.indexOf(token)
            while (from >= 0) {
                val before = if (from == 0) ' ' else text[from - 1]
                if (!before.isLetterOrDigit() && before != '_' && before != '.') {
                    val open = from + token.length - 1
                    var depth = 0
                    var i = open
                    while (i < text.length) {
                        when (text[i]) {
                            '(' -> depth++
                            ')' -> if (--depth == 0) break
                        }
                        i++
                    }
                    add(text.substring(open + 1, minOf(i, text.length)))
                }
                from = text.indexOf(token, from + 1)
            }
        }
    }

    private val throwableNames = listOf("e", "error", "it", "throwable", "failure", "exception", "cause", "t")

    private fun exceptionTextInDiagnosticSpans(path: String, text: String): List<String> = buildList {
        val names = throwableNames.joinToString("|")
        for (span in diagnosticSpans(text)) {
            if (span.contains(".message")) add("$path span:.message")
            if (Regex("""\b(?:$names)\.toString\(\)""").containsMatchIn(span)) add("$path span:toString")
            if (Regex("""\.format\([^)]*\b(?:$names)\b""").containsMatchIn(span)) add("$path span:format")
            for (name in throwableNames) {
                if (Regex("""\${'$'}$name\b""").containsMatchIn(span)) add("$path span:\$$name")
                if (span.contains("\${$name}")) add("$path span:\${$name}")
            }
        }
    }

    private fun threeArgumentLogCalls(path: String, text: String): List<String> = buildList {
        for (level in listOf("Log.e(", "Log.w(")) {
            var from = text.indexOf(level)
            while (from >= 0) {
                val open = from + level.length - 1
                var depth = 0
                var commas = 0
                var i = open
                while (i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) break
                        ',' -> if (depth == 1) commas++
                    }
                    i++
                }
                if (commas >= 2) add("$path ${level.dropLast(1)}:3")
                from = text.indexOf(level, from + 1)
            }
        }
    }

    private companion object {
        val POSITIVE = """
            import android.util.Log
            android.util.Log.i("T", "x")
            Log.getStackTraceString(e)
            e.printStackTrace()
            val frames = e.stackTrace
            e.stackTraceToString()
            e.localizedMessage
            e.cause?.message
            e.cause!!.message
            DebugLogger.warn(TAG, "a: ${'$'}{e.message}")
            DebugLogger.log(TAG, "b: " + e.toString())
            DebugLogger.error(TAG, String.format("%s", failure))
            DebugLogger.debug(TAG, "%s".format(error))
            log.warn("c ${'$'}e ${'$'}error ${'$'}it ${'$'}throwable ${'$'}failure ${'$'}exception ${'$'}cause ${'$'}t")
            log.error(
                "d ${'$'}{e} ${'$'}{error} ${'$'}{it} ${'$'}{throwable} ${'$'}{failure} ${'$'}{exception} ${'$'}{cause} ${'$'}{t}",
            )
            Log.e(TAG, "m", e)
            Log.w(TAG, "m", e)
        """.trimIndent()

        val ALLOWED = """
            DebugLogger.log(TAG, String.format("%.1f", durationSec))
            log.error("Transcription failed", error)
            DebugLogger.warn(TAG, "took ${'$'}{elapsed}ms tag=${'$'}tag text=${'$'}textChars items=${'$'}{it.javaClass.simpleName}")
            DebugLogger.error(TAG, "read failed", failure)
            failure.report(AsrFailureReason.DECODE_FAILED, e.javaClass.simpleName)
            Log.e(TAG, render(message, throwable))
            Log.w(TAG, message)
        """.trimIndent()
    }
}
