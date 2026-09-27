package com.envi.wispr.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#378, `kotlin-patterns.md` RULE: no-content-in-diagnostics as narrowed by #378). Read off the
 * raw source of `app/src/main`, the same way `DiagnosticsShapeTest` reads it:
 *
 * (a) a take's words reach a sink through ONE door: `LocalLog.words(` is called only from `TakeLog.kt`, and
 *     `LocalLog.line(` only from `DebugLogger.kt`, so nothing hands words to the file except a take's logger;
 * (b) the take-scoped owner `TakeRoute` never calls `DebugLogger` directly, so none of its lines can be written
 *     without its take id;
 * (c) the log's paths are built only by their owners (`LogFiles(` in `LocalLog`, `DeveloperSwitches`,
 *     `DeveloperLogs`);
 * (d) the adb door refuses every caller but the adb shell on every entry, and its manifest gate is DUMP;
 * (e) neither switch nor the unlock is ever named in telemetry sources.
 *
 * Each row names its REVERT; the positive control is that every owner file named here exists and is read.
 */
class LocalLogShapeTest {
    private val main = File("src/main/java/com/envi/wispr")
    private val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun rel(file: File) = file.path.removePrefix("src/main/java/com/envi/wispr/")

    @Test fun theTreeAndTheOwnersAreRead() {
        assertTrue(sources.size > 100)
        listOf("debug/TakeLog.kt", "debug/DebugLogger.kt", "debug/LocalLog.kt", "audio/TakeRoute.kt", "debug/DeveloperLogProvider.kt")
            .forEach { name -> assertTrue("$name is read", sources.any { rel(it) == name }) }
    }

    /** REVERT: call `LocalLog.words(` from any other file, e.g. the session coordinator. */
    @Test fun wordsReachTheFileOnlyThroughATakesLogger() {
        assertEquals(listOf("debug/TakeLog.kt"), sources.filter { it.readText().contains("LocalLog.words(") }.map(::rel))
        assertEquals(listOf("debug/DebugLogger.kt"), sources.filter { it.readText().contains("LocalLog.line(") }.map(::rel))
    }

    /** REVERT: add a `DebugLogger.log(` line to `TakeRoute`. */
    @Test fun theRouteLogsOnlyThroughItsTake() {
        assertFalse(File(main, "audio/TakeRoute.kt").readText().contains("DebugLogger."))
    }

    /** REVERT: construct `LogFiles(` anywhere else. */
    @Test fun onlyTheOwnersBuildTheLogPaths() {
        assertEquals(
            listOf("debug/DeveloperLogs.kt", "debug/DeveloperSwitches.kt", "debug/LocalLog.kt"),
            sources.filter { it.readText().contains("LogFiles(") && rel(it) != "debug/LogFiles.kt" }.map(::rel).sorted(),
        )
    }

    /** REVERT: drop `requireShell()` from `openFile`, `call` or `query`; or change the manifest permission. */
    @Test fun theAdbDoorOpensOnlyForTheShell() {
        val provider = File(main, "debug/DeveloperLogProvider.kt").readText()
        assertTrue(provider.contains("Binder.getCallingUid() != Process.SHELL_UID"))
        for (entry in listOf("override fun openFile(", "override fun call(", "override fun query(")) {
            val body = provider.substringAfter(entry).substringBefore("\n    }")
            assertTrue("$entry checks the caller first", body.contains("requireShell()"))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".debug.DeveloperLogProvider\"").substringBefore("/>")
        assertTrue(entry.contains("android:readPermission=\"android.permission.DUMP\""))
        assertTrue(entry.contains("android:writePermission=\"android.permission.DUMP\""))
    }

    /** REVERT: send a settings event for either switch. */
    @Test fun theSwitchesNeverReachTelemetry() {
        val telemetry = sources.filter { rel(it).startsWith("telemetry/") }
        assertTrue(telemetry.isNotEmpty())
        for (file in telemetry) {
            val text = file.readText()
            for (name in listOf("detailedLog", "keepRecordings", "developerUnlocked", "DeveloperSwitches")) {
                assertFalse("${rel(file)} names $name", text.contains(name))
            }
        }
    }
}
