package com.envi.wispr.ui

import java.io.File

/**
 * Harness Contract (#216): the session owner's source, as the rows that read it as text need it. The owner
 * delegates to two collaborators, so a row that pins code reads the ONE file that code lives in, and a row
 * that says something is ABSENT reads [all]: a negative scan over the owner alone would pass the moment the
 * code it forbids moved next door.
 */
internal object SessionSources {
    private fun read(name: String): String {
        val file = File("src/main/java/com/envi/wispr/ui/$name")
        check(file.isFile) { "the session source ${file.path} must exist" }
        return file.readText()
    }

    val coordinator: String get() = read("DictationSessionCoordinator.kt")
    val capture: String get() = read("CaptureSessionController.kt")
    val finalizer: String get() = read("SessionFinalizer.kt")
    val context: String get() = read("TakeContext.kt")
    /** The take's polish since #237. */
    val polish: String get() = read("TakePolishController.kt")
    /** The recorder notices and where they are said, since #256. */
    val notices: String get() = read("SessionNotice.kt")
    /** The steps before the bind since #281. */
    val preparer: String get() = read("TakeStartPreparer.kt")

    /** The take's outcome record since #329 (telemetry package). */
    val recorder: String get() = File("src/main/java/com/envi/wispr/telemetry/TakeOutcomeRecorder.kt").also {
        check(it.isFile) { "the session source ${it.path} must exist" }
    }.readText()

    /** Every file of the session owner, for a scan that says something is absent. */
    val all: String get() = listOf(coordinator, capture, finalizer, context, polish, notices, preparer, recorder).joinToString("\n")
}
