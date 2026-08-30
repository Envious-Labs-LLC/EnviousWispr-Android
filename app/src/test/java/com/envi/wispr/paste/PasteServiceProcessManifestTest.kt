package com.envi.wispr.paste

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * DRIFT GUARD. Explicitly NOT product coverage (`testing-philosophy.md`
 * RULE: every-test-declares-which-of-four-things-it-protects).
 *
 * The whole auto-paste readiness design rests on one manifest fact: the accessibility service
 * declares no `android:process`, so it shares the default process with the view model and the
 * session owner and its `isBound` flow is readable there. Adding `android:process` later would make
 * that flow a permanent false in the UI process, with no compile error, no user-visible error, and
 * nothing else in the repository that would notice.
 */
class PasteServiceProcessManifestTest {

    @Test
    fun theAccessibilityServiceStaysInTheDefaultProcess() {
        val service = serviceElement(".paste.PasteAccessibilityService")
        assertFalse(
            "PasteAccessibilityService declares android:process, so PasteAccessibilityService.isBound " +
                "can no longer be read by the UI process and auto-paste readiness reports a permanent false",
            service.hasAttribute("android:process"),
        )
    }

    /** Two-way control: without it the assertion above could pass by matching nothing. */
    @Test
    fun theSpeechServiceStillDeclaresItsOwnProcess() {
        val service = serviceElement(".asr.AsrService")
        assertEquals(":asr", service.getAttribute("android:process"))
    }

    private fun serviceElement(name: String): Element {
        val manifest = manifestFile()
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifest)
        val services = document.getElementsByTagName("service")
        assertTrue("No <service> elements were parsed from ${manifest.absolutePath}", services.length > 0)
        for (index in 0 until services.length) {
            val element = services.item(index) as Element
            if (element.getAttribute("android:name") == name) return element
        }
        fail("No <service android:name=\"$name\"> in ${manifest.absolutePath}")
        error("unreachable")
    }

    private fun manifestFile(): File {
        // A wrong working directory must fail loudly rather than pass vacuously.
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0L }
            ?: throw AssertionError(
                "AndroidManifest.xml was not found from working directory ${File(".").absolutePath}",
            )
    }
}
