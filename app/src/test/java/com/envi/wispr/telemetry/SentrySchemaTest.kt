package com.envi.wispr.telemetry

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.App
import io.sentry.protocol.Contexts
import io.sentry.protocol.Device
import io.sentry.protocol.Feedback
import io.sentry.protocol.Gpu
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryRuntime
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * Product Outcome (#240, REF-07): what a person says never leaves the phone in a crash report, however short.
 * Sentry text passes only as a shape `SentrySchema` declares; everything else is `[REDACTED]` or removed.
 * The SDK types are plain Java, so the final event is built and walked on the JVM.
 */
class SentrySchemaTest {
    private val words = "Meet me at six"
    private val take = "0a1b2c3d-4e5f-4a6b-8c7d-9e8f7a6b5c4d"

    /** Every string reachable from [root] the way the SDK would serialise it: fields (not transient), lists, maps, arrays. */
    private fun strings(root: Any?): List<String> {
        val out = mutableListOf<String>()
        val seen = IdentityHashMap<Any, Unit>()
        fun walk(value: Any?) {
            when (value) {
                null -> return
                is String -> out += value
                is CharSequence -> out += value.toString()
                is Number, is Boolean, is Enum<*>, is java.util.Date, is java.util.TimeZone, is Class<*> -> return
                is Map<*, *> -> value.forEach { (k, v) -> walk(k); walk(v) }
                is Iterable<*> -> value.forEach(::walk)
                is Array<*> -> value.forEach(::walk)
                else -> {
                    if (!value.javaClass.name.startsWith("io.sentry")) return
                    if (seen.put(value, Unit) != null) return
                    var type: Class<*>? = value.javaClass
                    while (type != null && type.name.startsWith("io.sentry")) {
                        for (field in type.declaredFields) {
                            if (Modifier.isStatic(field.modifiers) || Modifier.isTransient(field.modifiers)) continue
                            field.isAccessible = true
                            walk(field.get(value))
                        }
                        type = type.superclass
                    }
                }
            }
        }
        walk(root)
        return out
    }

    private fun frame(text: String) = SentryStackFrame().apply {
        filename = text; function = text; module = text; absPath = text; `package` = text; symbol = text
        rawFunction = text; platform = text; addrMode = text; contextLine = text; vars = mapOf("v" to text)
    }

    /**
     * Row 1, the product row: ordinary short prose on every surface, and none of it survives. MUTATIONS (one per
     * surface family): pass the event message through; keep tags raw; keep extras raw; pass the breadcrumb message
     * through; keep breadcrumb data raw; pass typed contexts unchanged; skip the frame function validator; keep
     * the user.
     */
    @Test fun ordinaryProseNeverLeavesOnAnySurface() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = words; message = words; params = listOf(words) }
            exceptions = listOf(SentryException().apply {
                type = words; value = words; module = words
                mechanism = Mechanism().apply { type = words; description = words; helpLink = words; data = mapOf("d" to words) }
                stacktrace = SentryStackTrace(listOf(frame(words))).apply { registers = mapOf("x0" to words) }
            })
            threads = listOf(SentryThread().apply { name = words; state = words; stacktrace = SentryStackTrace(listOf(frame(words))) })
            setTag(SentryBootstrap.TAG_PROCESS, words)
            setTag("free", words)
            setExtra("reason", words)
            setExtra("free", words)
            user = User().apply { name = words; email = "a@b.co"; username = words }
            request = Request().apply { url = words; data = words }
            fingerprints = listOf(words)
            logger = words
            transaction = words
            breadcrumbs = listOf(Breadcrumb(words).apply { category = words; type = words; setData("reason", words); setData("free", words) })
            contexts.setDevice(Device().apply { name = words; model = words; manufacturer = words })
            contexts.setOperatingSystem(OperatingSystem().apply { rawDescription = words })
            contexts.setApp(App().apply { appName = words; viewNames = listOf(words) })
            contexts.setRuntime(SentryRuntime().apply { rawDescription = words })
            contexts.setGpu(Gpu().apply { name = words })
            contexts.put("custom", mapOf("note" to words))
            contexts.setFeedback(Feedback(words))
        }
        val leaked = strings(SentryBootstrap.sanitize(event)).filter { it.contains("Meet") || it.contains("six") }
        assertEquals("nothing the person said leaves: $leaked", emptyList<String>(), leaked)
    }

    /**
     * Row 6: a single ordinary word fails every key that takes text (a finite set, or a dynamic identifier's
     * validator), the message and a breadcrumb. MUTATION: give `reason` the generic token rule.
     */
    @Test fun aSingleOrdinaryWordIsRedactedUnderEveryFiniteKey() {
        val textKeys = SentrySchema.keys.filterValues { it !is SentrySchema.Shape.Number && it !is SentrySchema.Shape.Flag }.keys
        assertTrue("the text keys were found", textKeys.size >= 20)
        textKeys.forEach { key ->
            assertEquals("'hello' under $key", SentrySchema.Verdict.Redact, SentrySchema.judge(key, "hello"))
        }
        assertEquals("[REDACTED]", SentrySchema.eventMessage("hello"))
        assertEquals("[REDACTED]", SentrySchema.breadcrumbMessage("take", "hello"))
    }

    /** Row 2a: a defect event keeps everything we declared. MUTATION: loosen the UUID shape to any token (the prose take id then passes). */
    @Test fun aDefectEventKeepsItsDeclaredFacts() {
        val defect = AppDefect.PolishServiceDied
        val event = Telemetry.defectEvent(
            defect,
            mapOf("take_id" to take, "reason" to "COMPLETED", "polish_status" to 7, "late" to true, "free" to "x"),
            eventId = null, timestamp = null, tags = emptyMap(),
        )
        val out = SentryBootstrap.sanitize(event)
        assertEquals(defect.semanticId, out.message!!.formatted)
        assertEquals(defect.semanticId, out.getTag(SentryBootstrap.TAG_IDENTITY))
        assertEquals(listOf(defect.fingerprint), out.fingerprints)
        assertEquals(take, out.getTag(SentryBootstrap.TAG_TAKE_ID))
        assertEquals("COMPLETED", out.getExtra("reason"))
        assertEquals(7, out.getExtra("polish_status"))
        assertEquals(true, out.getExtra("late"))
        assertNull("an undeclared key is dropped", out.getExtra("free"))
        val bad = SentryBootstrap.sanitize(Telemetry.defectEvent(defect, mapOf("take_id" to "meet-me"), null, null, emptyMap()))
        assertEquals("a declared key with a bad value keeps the key", "[REDACTED]", bad.getExtra("take_id"))
        // With no take live, a take id of the wrong shape leaves no take tag at all (the live-take fallback).
        assertNull("a take id of the wrong shape never becomes the tag", bad.getTag(SentryBootstrap.TAG_TAKE_ID))
    }

    /** Row 2b: a declared breadcrumb keeps its pair and data. MUTATION: accept an undeclared key (`free` then survives). */
    @Test fun aDeclaredBreadcrumbKeepsItsPairAndData() {
        val crumb = Breadcrumb("terminal").apply { category = "take"; setData("take_id", take); setData("reason", "COMPLETED"); setData("result", "completed"); setData("free", "x") }
        val out = SentryBootstrap.sanitize(crumb)
        assertEquals("terminal", out.message)
        assertEquals("take", out.category)
        assertEquals(take, out.data["take_id"])
        assertEquals("COMPLETED", out.data["reason"])
        assertEquals("completed", out.data["result"])
        assertNull(out.data["free"])
    }

    /** Row 2c: code locations survive per language. MUTATION: drop the `<init>` form from the method validator. */
    @Test fun codeLocationsSurviveForKotlinJavaAndNativeFrames() {
        val jvm = SentryStackFrame().apply {
            filename = "DictationSessionCoordinator.kt"; function = "<init>"; module = "com.envi.wispr.ui.DictationSessionCoordinator\$polish\$1"
            absPath = "/data/user/0/com.envi.wispr/files/x.kt"; lineno = 12
        }
        val lambda = SentryStackFrame().apply { function = "lambda\$publishResult\$0"; module = "com.envi.wispr.ui.TakePolishController" }
        val native = SentryStackFrame().apply {
            `package` = "/data/app/~~abc==/com.envi.wispr-xyz==/lib/arm64/libgeniex.so"; symbol = "_ZN6geniex4loadEv"
            function = "geniex::load(int, char const*)"; instructionAddr = "0x7a1b2c"; platform = "native"
        }
        val event = SentryEvent().apply {
            exceptions = listOf(SentryException().apply { type = "IllegalStateException"; module = "java.lang"; stacktrace = SentryStackTrace(listOf(jvm, lambda, native)) })
        }
        val ex = SentryBootstrap.sanitize(event).exceptions!!.single()
        assertEquals("IllegalStateException", ex.type)
        assertEquals("java.lang", ex.module)
        val (a, b, c) = ex.stacktrace!!.frames!!
        assertEquals("DictationSessionCoordinator.kt", a.filename)
        assertEquals("<init>", a.function)
        assertEquals("com.envi.wispr.ui.DictationSessionCoordinator\$polish\$1", a.module)
        assertEquals("[PATH]", a.absPath)
        assertEquals(12, a.lineno)
        assertEquals("lambda\$publishResult\$0", b.function)
        assertEquals("/data/app/~~abc==/com.envi.wispr-xyz==/lib/arm64/libgeniex.so", c.`package`)
        assertEquals("_ZN6geniex4loadEv", c.symbol)
        assertEquals("geniex::load(int, char const*)", c.function)
        assertEquals("0x7a1b2c", c.instructionAddr)
    }

    /**
     * Row 7: typed contexts. MUTATIONS: pass typed contexts unchanged (the Feedback context stays); skip the field
     * validation (prose in `manufacturer` stays); keep an unapproved context.
     */
    @Test fun onlyApprovedTypedContextsStayWithValidatedFields() {
        val event = SentryEvent().apply {
            contexts.setDevice(Device().apply { name = "Saurabh's Galaxy"; model = "SM-S938U"; manufacturer = words })
            contexts.setFeedback(Feedback("please fix"))
        }
        val out = SentryBootstrap.sanitize(event).contexts
        assertNull("the user-set device name is cleared", out.device!!.name)
        assertEquals("an approved device value is kept", "SM-S938U", out.device!!.model)
        assertEquals("prose in an approved field is redacted", "[REDACTED]", out.device!!.manufacturer)
        assertNull("a feedback context is removed", out.feedback)
        assertFalse(out.keys().toList().contains(Feedback.TYPE))
    }

    /**
     * Row 5: automatic events keep what groups and diagnoses them: exception type, frames, an absent fingerprint
     * stays absent, and the option tags. These are sanitizer tests on JVM fixtures; ANR and native grouping in
     * production is only as verified as the PR's bytecode note says.
     */
    @Test fun automaticCrashAnrAndNativeEventsKeepTheirDiagnosticShape() {
        fun tagged(event: SentryEvent) = event.apply { setTag(SentryBootstrap.TAG_PROCESS, "main"); setTag(SentryBootstrap.TAG_BUILD_TYPE, "release") }
        val crash = tagged(SentryEvent(RuntimeException(words)).apply {
            exceptions = listOf(SentryException().apply {
                type = "RuntimeException"; value = words; module = "java.lang"
                mechanism = Mechanism().apply { type = "UncaughtExceptionHandler"; isHandled = false }
                stacktrace = SentryStackTrace(listOf(SentryStackFrame().apply { function = "run"; module = "com.envi.wispr.audio.CaptureThread"; filename = "CaptureThread.kt"; lineno = 3 }))
            })
        })
        val anr = tagged(SentryEvent().apply {
            exceptions = listOf(SentryException().apply { type = "ApplicationNotResponding"; module = "io.sentry.android.core"; mechanism = Mechanism().apply { type = "ANR" } })
            threads = listOf(SentryThread().apply { name = "main"; state = "BLOCKED"; isMain = true })
        })
        val native = tagged(SentryEvent().apply {
            platform = "native"
            exceptions = listOf(SentryException().apply { type = "SIGSEGV"; mechanism = Mechanism().apply { type = "signalhandler" } })
        })
        for (event in listOf(crash, anr, native)) {
            val out = SentryBootstrap.sanitize(event)
            assertNull("an absent fingerprint stays absent", out.fingerprints)
            assertEquals("main", out.getTag(SentryBootstrap.TAG_PROCESS))
            assertEquals("release", out.getTag(SentryBootstrap.TAG_BUILD_TYPE))
        }
        val c = SentryBootstrap.sanitize(crash).exceptions!!.single()
        assertEquals("RuntimeException", c.type)
        assertNull(c.value)
        assertEquals("UncaughtExceptionHandler", c.mechanism!!.type)
        assertEquals("run", c.stacktrace!!.frames!!.single().function)
        val a = SentryBootstrap.sanitize(anr)
        assertEquals("ApplicationNotResponding", a.exceptions!!.single().type)
        assertEquals("ANR", a.exceptions!!.single().mechanism!!.type)
        assertEquals("main", a.threads!!.single().name)
        assertEquals("SIGSEGV", SentryBootstrap.sanitize(native).exceptions!!.single().type)
        assertEquals("native", SentryBootstrap.sanitize(native).platform)
    }

    /**
     * Row 5b: the SDK's own ANR fingerprints (read from the pinned 8.57.0 bytecode) leave unchanged, so ANR
     * grouping is what the SDK chose. MUTATION: drop the SDK's ANR words from the allowed fingerprints.
     */
    @Test fun theSdksAnrFingerprintsLeaveUnchanged() {
        for (fingerprint in listOf(listOf("{{ default }}", "foreground-anr"), listOf("system-frames-only-anr", "background-anr"))) {
            val out = SentryBootstrap.sanitize(SentryEvent().apply { fingerprints = fingerprint })
            assertEquals(fingerprint, out.fingerprints)
        }
    }

    // ---- the source rows: what the code sends is what the schema declares ------------------------------------

    private val sources: List<Pair<String, String>> =
        File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.path to it.readText() }.toList()

    /** The argument text of every call to [name] (balanced parentheses, so multiline calls are whole). */
    private fun calls(name: Regex): List<Pair<String, String>> = sources.flatMap { (path, text) ->
        name.findAll(text).mapNotNull { match ->
            val lineStart = text.lastIndexOf('\n', match.range.first) + 1
            val prefix = text.substring(lineStart, match.range.first)
            if ("fun " in prefix || prefix.trim().startsWith("*") || prefix.trim().startsWith("//")) return@mapNotNull null
            var depth = 0
            var i = match.range.last
            while (i < text.length) {
                when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) break } }
                i++
            }
            path to text.substring(match.range.last + 1, i)
        }.toList()
    }

    /** Row 3a: every breadcrumb this app writes is a declared pair. MUTATION: remove one declared message. */
    @Test fun everyBreadcrumbInTheSourceIsDeclared() {
        val crumbs = calls(Regex("(?<![A-Za-z_])(Telemetry\\.)?breadcrumb\\("))
        assertTrue("the scan found the breadcrumb calls", crumbs.size >= 12)
        crumbs.forEach { (path, args) ->
            val pair = Regex("\\A\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"").find(args)
            assertTrue("$path: a breadcrumb with a non-literal category or message: $args", pair != null)
            val (category, message) = pair!!.destructured
            assertTrue("$path: breadcrumb $category/$message is not declared in SentrySchema", SentrySchema.breadcrumbs[category].orEmpty().contains(message))
        }
    }

    /** Row 3b: every key a telemetry call sends to Sentry is declared. MUTATION: remove one declared key. */
    @Test fun everySentryKeyInTheSourceIsDeclared() {
        val maps = calls(Regex("(?<![A-Za-z_])(Telemetry\\.defect|defectSink|(Telemetry\\.)?breadcrumb)\\("))
        val literalKeys = maps.flatMap { (path, args) -> Regex("\"([A-Za-z0-9_.]+)\"\\s+to\\b").findAll(args).map { path to it.groupValues[1] }.toList() }
        val setCalls = calls(Regex("\\.(setTag|setExtra)\\(")).mapNotNull { (path, args) ->
            Regex("\\A\\s*\"([A-Za-z0-9_.]+)\"").find(args)?.let { path to it.groupValues[1] }
        }
        assertTrue("the scan found the keys", literalKeys.size >= 30)
        (literalKeys + setCalls).forEach { (path, key) ->
            assertTrue("$path: key '$key' reaches Sentry but is not declared in SentrySchema", key in SentrySchema.keys)
        }
    }

    /** Row 3c: every defect type is in the schema's message and fingerprint sets. MUTATION: drop one defect from the schema's list. */
    @Test fun everyDefectTypeIsDeclared() {
        val declared = SentrySchema.defects.map { it.javaClass }.toSet()
        val subtypes = AppDefect::class.java.declaredClasses.filter { AppDefect::class.java.isAssignableFrom(it) }
        assertTrue("the scan found the defect types", subtypes.size >= 17)
        subtypes.forEach { subtype -> assertTrue("${subtype.simpleName} is not declared in SentrySchema", subtype in declared) }
        SentrySchema.defects.forEach { defect ->
            assertTrue(defect.semanticId in SentrySchema.eventMessages)
            assertTrue(defect.fingerprint in SentrySchema.fingerprints)
        }
    }

    /**
     * Row 3d, the seam inventory: every text-bearing field of the pinned SDK types the seam handles is in
     * `SentryBootstrap.HANDLED_FIELDS`, so an SDK upgrade that adds one cannot pass unexamined. MUTATION: remove
     * one entry from the table.
     */
    @Test fun everyTextBearingSdkFieldHasADeclaredHandling() {
        val types = listOf(
            io.sentry.SentryBaseEvent::class.java, SentryEvent::class.java, Message::class.java, SentryException::class.java,
            Mechanism::class.java, SentryThread::class.java, SentryStackTrace::class.java, SentryStackFrame::class.java,
            Breadcrumb::class.java, Contexts::class.java, Device::class.java, OperatingSystem::class.java, App::class.java,
            SentryRuntime::class.java, Gpu::class.java,
        )
        fun textBearing(type: Class<*>): Boolean = when {
            type.isPrimitive || Number::class.java.isAssignableFrom(type) || type == java.lang.Boolean::class.java -> false
            type.isEnum || type == java.util.Date::class.java || type == java.util.TimeZone::class.java -> false
            else -> true
        }
        val missing = types.flatMap { type ->
            type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && textBearing(it.type) }
                .map { "${type.simpleName}.${it.name}" }
                .filter { it !in SentryBootstrap.HANDLED_FIELDS }
        }
        assertEquals("text-bearing SDK fields with no declared handling", emptyList<String>(), missing)
        val walked = SentryBootstrap.HANDLED_FIELDS.filterValues { it == "walked" }.keys
        assertTrue("the walked fields are the event's containers", walked.containsAll(listOf("SentryEvent.exception", "SentryEvent.threads", "SentryBaseEvent.contexts")))
    }
}
