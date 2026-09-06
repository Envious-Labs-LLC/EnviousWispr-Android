package com.envi.wispr.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome for the round trip, Drift Guard for the wiring, and the cases say which.
 *
 * The outcome this protects: an hour spent building a word list can be saved somewhere it survives, and
 * read back. Before this, Export put the list on the clipboard, which is not a backup and cannot reach
 * another phone.
 */
class VocabularyExportTest {

    private val screen = File("src/main/java/com/envi/wispr/ui/DictionaryScreen.kt").readText()

    // ---- Product Outcome: a saved file can be read back ----

    @Test
    fun everythingWrittenOutComesBackIn() {
        // Built with LITERALS, so both sides are not transformed by the same machinery. The adversarial
        // characters are the ones the format's own escaping has to survive: its two delimiters.
        val source = listOf(
            CustomTerm("EnviousWispr", aliases = listOf("envious whisper", "envious wispa")),
            CustomTerm("Straße", aliases = listOf("strasse")),
            CustomTerm("tab\there", aliases = listOf("comma,alias")),
            CustomTerm("line\nbreak"),
        )
        val preview = VocabularyTransfer.preview(VocabularyTransfer.export(source))
        assertEquals(
            "every word must survive the trip",
            listOf("EnviousWispr", "Straße", "tab\there", "line\nbreak"),
            preview.accepted.map { it.spelling },
        )
        assertEquals(
            "and so must its aliases",
            listOf("envious whisper", "envious wispa"),
            preview.accepted.first().aliases,
        )
        assertEquals("nothing may be silently dropped", 0, preview.rejected)
    }

    @Test
    fun anEmptyListExportsToSomethingThatStillReadsBack() {
        val preview = VocabularyTransfer.preview(VocabularyTransfer.export(emptyList()))
        assertEquals(0, preview.accepted.size)
        assertEquals(0, preview.rejected)
    }

    // ---- The file's own promises ----

    @Test
    fun theFileClaimsToBeWhatItIs() {
        // The format is a header line then tab-separated lines. Calling it JSON or CSV would make a
        // picker offer it to apps that would read it wrongly.
        assertEquals("text/plain", VocabularyExport.MIME_TYPE)
        assertTrue(
            "the suggested name must be recognisable a year later",
            VocabularyExport.FILENAME.startsWith("enviouswispr") && VocabularyExport.FILENAME.endsWith(".txt"),
        )
    }

    // ---- Drift Guard: the wiring ----

    @Test
    fun exportWritesAFileAndNoLongerTouchesTheClipboard() {
        assertTrue(
            "export must open a document writer",
            screen.contains("ActivityResultContracts.CreateDocument(VocabularyExport.MIME_TYPE)"),
        )
        // The clipboard is not merely unused here; the point is that exporting can no longer make the
        // insertion fallback's own instruction false. "Copied. Press and hold, then tap Paste." was
        // still standing while this button replaced what was there.
        listOf("ClipboardManager", "ClipData", "setPrimaryClip").forEach { symbol ->
            assertFalse("the dictionary screen must not touch the clipboard: $symbol", screen.contains(symbol))
        }
    }

    // ---- Product Outcome: a saved file is a file that reads back ----

    @Test
    fun aWordListThatWouldNotReadBackIsRefusedRatherThanSaved() {
        // export() refuses on the TERM COUNT. preview(), which is what reads the file back, also refuses
        // on aliases per term. So a list could save happily and fail to import, and the user would find
        // out on the day they needed it. 257 aliases is one over preview's limit.
        val tooManyAliases = listOf(
            CustomTerm("EnviousWispr", aliases = (1..257).map { "alias$it" }),
        )
        val failure = runCatching { VocabularyExport.verifiedPayload(tooManyAliases) }.exceptionOrNull()
        assertTrue("a list the importer would reject must not be saved", failure != null)
        assertTrue(
            "and the refusal must be readable: ${failure?.message}",
            failure?.message?.contains("reads back completely") == true,
        )
        // The asymmetry is real and this is the receipt: export produced the row happily, and the
        // importer rejected exactly one.
        assertTrue(
            "the refusal must name how many words the importer would drop: ${failure?.message}",
            failure?.message?.startsWith("1 of your words") == true,
        )
    }

    @Test
    fun anOrdinaryListPassesTheRoundTripCheckAndIsReturnedUnchanged() {
        val terms = listOf(
            CustomTerm("EnviousWispr", aliases = listOf("envious whisper")),
            CustomTerm("Straße"),
        )
        assertEquals(
            "a list that reads back must be written exactly as export produced it",
            VocabularyTransfer.export(terms),
            VocabularyExport.verifiedPayload(terms),
        )
    }

    @Test
    fun aRefusedExportCannotTruncateTheFileTheUserChose() {
        // `VocabularyTransfer.export` refuses above its own limit. Building the payload before opening
        // the stream means that refusal happens with nothing written; building it after would leave the
        // user's chosen file emptied by "wt" and then abandoned.
        val body = screen.substringAfter("val exportFile = rememberLauncherForActivityResult")
            .substringBefore("var selectedIds")
        val built = body.indexOf("VocabularyExport.verifiedPayload(terms)")
        val opened = body.indexOf("openOutputStream(uri")
        assertTrue("both steps must be present", built >= 0 && opened >= 0)
        assertTrue("the payload must be built before the file is opened", built < opened)
    }

    @Test
    fun aFailedSaveDoesNotLeaveSomethingThatLooksLikeABackup() {
        // The dangerous shape: a half-written file imports cleanly as a SMALLER word list, so the user
        // keeps a backup that quietly lost words. The picker has already created the document by the
        // time anything can fail, so every incomplete exit has to clean up after itself.
        //
        // THE ENUMERATION IS THE `finally`, not a list of failures. Three review rounds each found a
        // different way out: a write that failed part way, a cancellation during the write, and a
        // cancellation before the write started. A block that runs on every exit covers those and any
        // fourth; patching them one at a time could not.
        val body = screen.substringAfter("val exportFile = rememberLauncherForActivityResult")
            .substringBefore("var selectedIds")
        assertTrue(
            "cleanup must run on every exit, not on a list of failures",
            body.contains("} finally {") && body.contains("if (!completed) {"),
        )
        // The last boundary is ENTRY. With the default start, a scope cancelled between the picker
        // returning and this coroutine being dispatched never runs at all, and the empty document the
        // picker just created survives with nobody responsible for it.
        assertTrue(
            "the body must be entered before anything can cancel it",
            screen.contains("scope.launch(start = CoroutineStart.UNDISPATCHED) {"),
        )
        assertTrue(
            "the flag must be set only after the write AND the close returned",
            body.contains("completed = true"),
        )
        assertTrue(
            "a failure must try to remove the file it left behind",
            body.contains("DocumentsContract.deleteDocument(context.contentResolver, uri)"),
        )
        assertTrue(
            "and must survive the cancellation that usually caused it",
            body.contains("withContext(NonCancellable + Dispatchers.IO)"),
        )
        assertTrue(
            "and must say which happened rather than guessing",
            body.contains("The incomplete file was removed.") &&
                body.contains("An incomplete file may remain, so delete it before trusting it."),
        )
    }

    @Test
    fun aCancelledSaveIsNotReportedToTheUserAsAnError() {
        // The screen a message would appear on is already gone, and a cancellation is not a save
        // failure. It still cleans up, it just says nothing.
        val body = screen.substringAfter("val exportFile = rememberLauncherForActivityResult")
            .substringBefore("var selectedIds")
        assertTrue(
            "a cancellation must not produce a user-facing error",
            body.contains("if (failure !is CancellationException) {"),
        )
        assertTrue(
            "and nothing is shown when there is nothing to say",
            body.contains("message?.let { Toast.makeText("),
        )
    }

    @Test
    fun theCountReportedIsTheCountSaved() {
        // export() drops blank spellings, so counting the input would overstate what is in the file.
        val body = screen.substringAfter("val exportFile = rememberLauncherForActivityResult")
            .substringBefore("var selectedIds")
        assertTrue(
            "the toast must count what export would keep",
            body.contains("terms.count { term -> term.spelling.isNotBlank() }"),
        )
    }

    @Test
    fun exportIsOfferedOnlyWhenThereIsSomethingToExport() {
        assertTrue(
            "an empty list must not offer a save dialog that writes nothing",
            screen.contains("enabled = allTerms.isNotEmpty(),"),
        )
    }
}
