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
 * (a) `android.util.Log` is imported (plain, aliased or through `android.util.*`) or named by exactly one
 *     file, `debug/DebugLogger.kt`, so every line reaches logcat through [DebugLogger.render]'s rendering
 *     of a throwable;
 * (b) nothing anywhere prints a stack (`printStackTrace`, `stackTraceToString`, the platform's
 *     stack-string helper), reads a localized message, or reaches into a cause's message; the throwable's
 *     stack-trace array is read in ONE place, the renderer;
 * (c) no diagnostic call span (a `DebugLogger.*`, `SessionLog` `log.*`, `logInfo`/`logWarn`, or the
 *     speech process's `failure.report(`) carries `.message`, a throwable's `toString()`, a template of a
 *     conventionally named throwable, or a `format` whose value argument is one;
 * (d) the renderer itself hands no throwable to a three-argument `Log.e`/`Log.w` and has no file sink;
 * (e) the four content-bearing sources the change reduced stay reduced: the debug insert receiver and
 *     the silence device test log a LENGTH and name the text nowhere else in the span, the delivery
 *     worker logs the host's closed token and names the host nowhere else on the line, and the llama.cpp
 *     log callback has an empty body.
 *
 * The scan reads RAW text, comments included: a comment that needs a forbidden token writes around it, as
 * the plan's citation checker already makes prose do. A call's span is its balanced-parenthesis argument
 * text, read by a scanner that skips parentheses and commas inside regular, raw (triple-quoted) and
 * character literals (code review round 1: a `)` inside a string ended the naive span EARLY, a silent
 * miss); a string literal nested inside a `${…}` template, and an apostrophe in a comment placed inside
 * a call's arguments, are the stated limits of that scanner. The
 * throwable-in-template check (c) is name-based, not type-aware: a throwable bound to an unconventional
 * name and templated bare (`"$oops"`) passes, and a non-throwable bound to `it` and templated bare inside
 * a diagnostic span reads as a LOUD false red (bind a descriptive name); a type-aware rule is a compiler
 * plugin away and out of this change's budget, so the reviewer's eye and [DebugLoggerRenderTest] are the
 * guard for the first shape.
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

    /** REVERT: add `import android.util.Log` (or `as L`, or `android.util.*`) to any other file, or call `android.util.Log.i(` inline. */
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
        assertEquals("no file sink", emptyList<String>(), fileSinkUses(owner, text))
        assertTrue("the owner carries stackTraceToString", !text.contains("stackTraceToString"))
    }

    /** REVERT: log `$host` or `${host.lowercase()}`, `$text` or `${text.take(20)}`, or put any statement back in the callback. */
    @Test
    fun specialContentSourcesStayReducedToShape() {
        val insert = File("src/debug/java/com/envi/wispr/debug/DebugInsertReceiver.kt").readText()
        assertEquals(emptyList<String>(), lengthOnlyViolations("DebugInsertReceiver", diagnosticSpans(insert), "textChars=\${text.length}", "text"))

        val silence = File("src/androidTest/java/com/envi/wispr/SilenceStoppedTakeTranscribesDeviceTest.kt").readText()
        assertEquals(emptyList<String>(), lengthOnlyViolations("SilenceStoppedTakeTranscribesDeviceTest", diagnosticSpans(silence), "chars=\${text.length}", "text"))

        val worker = File("src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt").readText()
        val sourceLine = worker.lines().single { it.contains("\"Model source: ") }
        assertEquals(emptyList<String>(), tokenOnlyViolations("ModelDeliveryWorker", sourceLine, "ModelSourceHost.of(host).wire", "host"))

        val jni = File("../llama-android/src/main/cpp/s1_jni.cpp").readText()
        assertEquals(emptyList<String>(), nativeCallbackViolations(jni))
    }

    // ---- the two-way control -------------------------------------------------------------------------

    @Test
    fun theScanNamesEveryForbiddenShapeInThePositiveFixture() {
        val found = scanAll("Fixture.kt", POSITIVE) +
            lengthOnlyViolations("Fixture", diagnosticSpans(POSITIVE_SPECIAL), "textChars=\${text.length}", "text") +
            tokenOnlyViolations("Fixture", POSITIVE_SPECIAL.lines().single { it.contains("Model source") }, "ModelSourceHost.of(host).wire", "host") +
            nativeCallbackViolations(POSITIVE_JNI)
        val expected = listOf(
            "import android.util.Log", "import android.util.Log as", "import android.util.*", "android.util.Log.",
            "getStackTraceString", "printStackTrace", ".stackTrace", "stackTraceToString", ".localizedMessage",
            ".cause?.message", ".cause!!.message",
            "span:.message", "span:toString", "span:format",
            "span:\$e", "span:\$error", "span:\$it", "span:\$throwable", "span:\$failure", "span:\$exception",
            "span:\$cause", "span:\$t",
            "span:\${e}", "span:\${error}", "span:\${it}", "span:\${throwable}", "span:\${failure}", "span:\${exception}",
            "span:\${cause}", "span:\${t}",
            "span:this.log", "span:after-a-paren-in-a-string", "span:fully-qualified",
            "Log.e:3", "Log.w:3", "Environment", "FileWriter",
            "special:text", "special:host", "special:callback-body",
        )
        val missing = expected.filter { shape -> found.none { it.endsWith(" $shape") } }
        assertEquals("the scan must name every planted shape; found=$found", emptyList<String>(), missing)
    }

    @Test
    fun theScanNamesNothingInTheAllowedFixture() {
        val found = scanAll("Allowed.kt", ALLOWED) +
            lengthOnlyViolations("Allowed", diagnosticSpans(ALLOWED_SPECIAL), "textChars=\${text.length}", "text") +
            tokenOnlyViolations("Allowed", ALLOWED_SPECIAL.lines().single { it.contains("Model source") }, "ModelSourceHost.of(host).wire", "host") +
            nativeCallbackViolations(ALLOWED_JNI)
        assertEquals(emptyList<String>(), found)
    }

    private fun scanAll(path: String, text: String): List<String> =
        androidLogUses(path, text) + stackOrChainUses(path, text) + stackTraceReads(path, text) +
            exceptionTextInDiagnosticSpans(path, text) + threeArgumentLogCalls(path, text) + fileSinkUses(path, text)

    // ---- the scan --------------------------------------------------------------------------------------

    private fun androidLogUses(path: String, text: String): List<String> = buildList {
        if (Regex("""(?m)^\s*import\s+android\.util\.Log\s*$""").containsMatchIn(text)) add("$path import android.util.Log")
        if (Regex("""(?m)^\s*import\s+android\.util\.Log\s+as\b""").containsMatchIn(text)) add("$path import android.util.Log as")
        if (Regex("""(?m)^\s*import\s+android\.util\.\*""").containsMatchIn(text)) add("$path import android.util.*")
        if (text.contains("android.util.Log.")) add("$path android.util.Log.")
    }

    private fun stackOrChainUses(path: String, text: String): List<String> = buildList {
        listOf("getStackTraceString", "printStackTrace", "stackTraceToString", ".localizedMessage", ".cause?.message", ".cause!!.message")
            .forEach { token -> if (text.contains(token)) add("$path $token") }
    }

    /** `.stackTrace` as a property read; `stackTraceToString` is (b)'s and `fillInStackTrace` is a different word. */
    private fun stackTraceReads(path: String, text: String): List<String> =
        Regex("""\.stackTrace\b""").findAll(text).map { "$path .stackTrace" }.toList()

    private fun fileSinkUses(path: String, text: String): List<String> = buildList {
        listOf("Environment", "FileWriter").forEach { token -> if (Regex("""\b$token\b""").containsMatchIn(text)) add("$path $token") }
    }

    private val diagnosticCallTokens = listOf(
        "DebugLogger.log(", "DebugLogger.warn(", "DebugLogger.error(", "DebugLogger.debug(",
        "log.log(", "log.warn(", "log.error(", "logInfo(", "logWarn(", "failure.report(",
    )

    /**
     * The balanced-parenthesis argument text of every diagnostic call; a multi-line call is one span. A
     * token may follow `.` (`this.log.warn(`, `com.envi.wispr.debug.DebugLogger.log(`) but not a word
     * character (`catalog.warn(`). Parentheses and commas inside string and character literals are skipped
     * by [scanArguments].
     */
    private fun diagnosticSpans(text: String): List<String> = buildList {
        for (token in diagnosticCallTokens) {
            var from = text.indexOf(token)
            while (from >= 0) {
                val before = if (from == 0) ' ' else text[from - 1]
                if (!before.isLetterOrDigit() && before != '_') {
                    add(scanArguments(text, from + token.length - 1).first)
                }
                from = text.indexOf(token, from + 1)
            }
        }
    }

    /**
     * From the `(` at [open], returns the argument text up to its matching `)` and the count of top-level
     * commas, treating `"…"` (with `\"` escapes), `"""…"""` and `'…'` (with `\'` escapes) as opaque.
     */
    private fun scanArguments(text: String, open: Int): Pair<String, Int> {
        var depth = 0
        var commas = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) text.length else end + 3
                    continue
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c) {
                        if (text[j] == '\\') j++
                        j++
                    }
                    i = j + 1
                    continue
                }
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return text.substring(open + 1, i) to commas
                c == ',' -> if (depth == 1) commas++
            }
            i++
        }
        return text.substring(open + 1) to commas
    }

    private val throwableNames = listOf("e", "error", "it", "throwable", "failure", "exception", "cause", "t")

    private fun exceptionTextInDiagnosticSpans(path: String, text: String): List<String> = buildList {
        val names = throwableNames.joinToString("|")
        for (span in diagnosticSpans(text)) {
            if (Regex("""\.message\b""").containsMatchIn(span)) add("$path span:.message")
            if (Regex("""\b(?:$names)\.toString\(\)""").containsMatchIn(span)) add("$path span:toString")
            if (Regex("""\.format\([^)]*\b(?:$names)\b""").containsMatchIn(span)) add("$path span:format")
            for (name in throwableNames) {
                if (Regex("""\${'$'}$name\b""").containsMatchIn(span)) add("$path span:\$$name")
                if (span.contains("\${$name}")) add("$path span:\${$name}")
            }
            // Markers the control fixture plants so a receiver-dotted, a fully qualified and a
            // paren-in-string call are each proven to yield a span at all.
            if (span.contains("THIS_LOG_MARKER")) add("$path span:this.log")
            if (span.contains("AFTER_PAREN_MARKER")) add("$path span:after-a-paren-in-a-string")
            if (span.contains("FQ_MARKER")) add("$path span:fully-qualified")
        }
    }

    private fun threeArgumentLogCalls(path: String, text: String): List<String> = buildList {
        for (level in listOf("Log.e(", "Log.w(")) {
            var from = text.indexOf(level)
            while (from >= 0) {
                if (scanArguments(text, from + level.length - 1).second >= 2) add("$path ${level.dropLast(1)}:3")
                from = text.indexOf(level, from + 1)
            }
        }
    }

    /** The span may name [name] only inside the one allowed length expression. */
    private fun lengthOnlyViolations(label: String, spans: List<String>, allowed: String, name: String): List<String> = buildList {
        if (spans.isEmpty()) add("$label no diagnostic span")
        if (spans.none { it.contains(allowed) }) add("$label no `$allowed`")
        for (span in spans) {
            if (Regex("""\b$name\b""").containsMatchIn(span.replace(allowed, ""))) add("$label special:$name")
        }
    }

    /** The line may name [name] only inside the closed-token expression. */
    private fun tokenOnlyViolations(label: String, line: String, allowed: String, name: String): List<String> = buildList {
        if (!line.contains(allowed)) add("$label no `$allowed`")
        if (Regex("""\b$name\b""").containsMatchIn(line.replace(allowed, ""))) add("$label special:$name")
    }

    private fun nativeCallbackViolations(jni: String): List<String> = buildList {
        val callback = Regex("""void llama_log_callback\([^)]*\)\s*\{([^}]*)\}""").find(jni)
        if (callback == null) {
            add("s1_jni.cpp no llama_log_callback")
        } else if (callback.groupValues[1].isNotBlank()) {
            add("s1_jni.cpp special:callback-body")
        }
        val prints = Regex("""__android_log_print""").findAll(jni).count()
        if (prints != 1) add("s1_jni.cpp prints=$prints")
        if (!Regex("""void log_info\([^)]*\)\s*\{[^}]*__android_log_print""").containsMatchIn(jni)) add("s1_jni.cpp log_info does not print")
    }

    private companion object {
        val POSITIVE = """
            import android.util.Log
            import android.util.Log as L
            import android.util.*
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
            this.log.warn("THIS_LOG_MARKER")
            DebugLogger.warn(TAG, "shape ) AFTER_PAREN_MARKER")
            com.envi.wispr.debug.DebugLogger.log(TAG, "FQ_MARKER")
            Log.e(TAG, "m", e)
            Log.w(TAG, "m", e)
            val dir = Environment.getExternalStorageDirectory()
            val out = FileWriter(file, true)
        """.trimIndent()

        val ALLOWED = """
            DebugLogger.log(TAG, String.format("%.1f", durationSec))
            log.error("Transcription failed", error)
            DebugLogger.warn(TAG, "took ${'$'}{elapsed}ms tag=${'$'}tag text=${'$'}textChars items=${'$'}{it.javaClass.simpleName} messageCount=${'$'}{box.messageCount}")
            DebugLogger.error(TAG, "read failed", failure)
            DebugLogger.log(TAG, "left ) alone: ${'$'}{count}")
            failure.report(AsrFailureReason.DECODE_FAILED, e.javaClass.simpleName)
            Log.e(TAG, render(message, throwable))
            Log.w(TAG, "a, b, c")
            catalog.warn("not a diagnostic: ${'$'}{e.message}")
        """.trimIndent()

        val POSITIVE_SPECIAL = """
            DebugLogger.log("DebugInsert", "pin=${'$'}pin textChars=${'$'}{text.length} head=${'$'}{text.take(20)}")
            DebugLogger.log(TAG, "Model source: ${'$'}{model.id} from ${'$'}{ModelSourceHost.of(host).wire} (${'$'}{host.lowercase()})")
        """.trimIndent()

        val ALLOWED_SPECIAL = """
            DebugLogger.log("DebugInsert", "pin=${'$'}pin handoff=${'$'}handoff textChars=${'$'}{text.length}")
            DebugLogger.log(TAG, "Model source: ${'$'}{model.id}/${'$'}file from ${'$'}{ModelSourceHost.of(host).wire}")
        """.trimIndent()

        val POSITIVE_JNI = """
            void log_info(const std::string & message) {
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message.c_str());
            }
            void llama_log_callback(ggml_log_level, const char * text, void *) {
                log_info(text);
            }
        """.trimIndent()

        val ALLOWED_JNI = """
            void log_info(const std::string & message) {
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message.c_str());
            }
            void llama_log_callback(ggml_log_level, const char *, void *) {
            }
        """.trimIndent()
    }
}
