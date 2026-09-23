package com.envi.wispr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#259): the AI Polish tab's presentation lives in three files by rung. `PolishScreen.kt` keeps
 * the root composable, its one-write state and the parts only it uses, plus the ladder's two shared parts;
 * `PolishCloudRungs.kt` holds the cloud rungs and the provider-key controls; `PolishLocalControls.kt` holds the
 * on-phone model cards. When this fails, a rung has moved back into the root file or been declared twice.
 */
class PolishScreenLayoutTest {
    private val ui = "src/main/java/com/envi/wispr/ui"
    private val files = listOf("PolishScreen.kt", "PolishCloudRungs.kt", "PolishLocalControls.kt")
        .associateWith { File("$ui/$it").readText() }

    private val home = mapOf(
        "PolishScreen.kt" to listOf("PolishScreen", "QuietCard", "RungOneButton", "OffGlyph", "PhoneGlyph", "CloudGlyph", "RungHeader", "ErrorLine"),
        "PolishCloudRungs.kt" to listOf("CloudRungs", "ModelRung", "GetKeyLink", "keyPlaceholder", "ProviderTileButton", "CheckGlyph"),
        "PolishLocalControls.kt" to listOf("S1Card", "S1ControlCard", "ControlAxis", "DevelopmentModelCard"),
    )

    private fun declares(text: String, name: String) =
        Regex("""(?m)^(?:internal |private )?fun $name\(""").findAll(text).count()

    /** MUTATION: move `DevelopmentModelCard` back into `PolishScreen.kt`. */
    @Test fun everyRungIsDeclaredInItsOwnFileAndNowhereElse() {
        home.forEach { (file, names) ->
            names.forEach { name ->
                files.forEach { (other, text) ->
                    assertEquals("$name declared in $other", if (other == file) 1 else 0, declares(text, name))
                }
            }
        }
        assertTrue("the enum the cloud rungs take stays with the root", files.getValue("PolishScreen.kt").contains("internal enum class WriteKind"))
        val rootLines = files.getValue("PolishScreen.kt").lines().size
        assertTrue("the root file stays small: $rootLines lines", rootLines < 450)
    }
}
