package com.envi.wispr.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Row 6 of #257: `AppDefect.all()` names every member declared in `DefectIdentity.kt`, read off the source, so a
 * new defect omitted from the list fails here (the snapshot in `TelemetryContractsTest` counts `all()` and cannot
 * see a member missing from both). MUTATION: drop `SilenceWriterExitWedged` from `AppDefect.all()`.
 */
class DefectListCompletenessTest {
    @Test fun allNamesEveryDeclaredDefect() {
        val source = File("src/main/java/com/envi/wispr/telemetry/DefectIdentity.kt").readText()
        val declared = Regex("""^\s*(?:object|class)\s+([A-Z][A-Za-z0-9]*)\b[^\n]*:\s*AppDefect\(""", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toSet()
        check(declared.size > 10) { "the declarations must be readable: $declared" }
        val listed = AppDefect.all().map { it.javaClass.simpleName }.toSet()
        assertEquals(declared, listed)
    }
}
