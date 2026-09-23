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

    /** Every file of the session owner, for a scan that says something is absent. */
    val all: String get() = listOf(coordinator, capture, finalizer, context).joinToString("\n")
}
