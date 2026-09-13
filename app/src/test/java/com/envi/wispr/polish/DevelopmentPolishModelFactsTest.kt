package com.envi.wispr.polish

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard, not product coverage.
 *
 * `DevelopmentPolishModel` needs a `Context` for every real question, so the JVM cannot ask it any of
 * them. What CAN be checked here is the thing that actually went wrong: the path and the qualification
 * test existed in one place, that place cared only whether the file was loadable, and so 469 MB sat on
 * the phone with nothing able to see it.
 *
 * These cases pin that there is now ONE owner and that the two questions stayed separate.
 */
class DevelopmentPolishModelFactsTest {

    private val owner = File("src/main/java/com/envi/wispr/polish/DevelopmentPolishModel.kt").readText()
    private val selector = File("src/main/java/com/envi/wispr/polish/S1ModelSelection.kt").readText()
    private val screen = File("src/main/java/com/envi/wispr/ui/PolishScreen.kt").readText()

    @Test
    fun thePathHasOneOwnerAndThePolishSideNoLongerBuildsItsOwn() {
        assertTrue(
            "the owner must name the directory",
            owner.contains("""File(context.noBackupFilesDir, "development-models")"""),
        )
        assertFalse(
            "the selector must not build the path again",
            selector.contains("development-models"),
        )
        assertFalse(
            "and must not hash the file again either",
            selector.contains("MessageDigest"),
        )
        assertTrue(
            "the selector must ask the owner",
            selector.contains("DevelopmentPolishModel.isSupported(context)") &&
                selector.contains("DevelopmentPolishModel.selectable(context)"),
        )
    }

    @Test
    fun theTwoQuestionsAboutTheSameFileStaySeparate() {
        // EXISTS is what the screen asks, SELECTABLE is what polish asks. Collapsing them hides the case
        // that matters most: a large file with the wrong hash, which is doing nothing and costing
        // everything.
        assertTrue("presence must be measurable on its own", owner.contains("fun bytesOnDisk("))
        assertTrue("and loadability separately", owner.contains("fun selectable("))
    }

    @Test
    fun theFileThatWasCheckedIsTheFileThatIsLoaded() {
        // Asking a Context for the directory again AFTER validating a file is a second question, and
        // only an identical answer makes them the same file. `selectable` hands back the very file it
        // tested so no caller can validate one path and load another.
        assertTrue("the owner returns the checked file", owner.contains("fun selectable(context: Context): File?"))
        assertTrue("and returns the candidate itself", owner.contains("return candidate"))
        assertFalse(
            "the selector must not rebuild the path after checking it",
            selector.contains("DevelopmentPolishModel.file(context)"),
        )
    }

    @Test
    fun theCardIsAbsentInABuildThatCouldNeverUseTheFile() {
        // Not disabled, absent. A release build never loads this file, so offering to manage it would
        // name a capability that build does not have.
        assertTrue(
            "the build check must be the first thing the card does",
            screen.contains("if (!DevelopmentPolishModel.isSupported(context)) return"),
        )
        assertTrue(
            "and it must be the debuggable flag rather than a build-type string",
            owner.contains("ApplicationInfo.FLAG_DEBUGGABLE"),
        )
    }

    @Test
    fun theRemovalIsNotAnUnattendedRecursiveSweep() {
        // tools-and-apps.md RULE: never-unattended-recursive-delete. Entries are visited one at a time,
        // links are never followed, and the answer comes from measuring the world afterwards rather
        // than from any exit code along the way.
        assertFalse("no blanket recursive delete", owner.contains("deleteRecursively"))
        assertTrue("entries go one at a time", owner.contains("Files.walkFileTree("))
        assertTrue("directories go bottom-up", owner.contains("override fun postVisitDirectory("))
        assertTrue(
            "a linked root is refused rather than deleted through",
            owner.contains("if (Files.isSymbolicLink(rootPath)) return false"),
        )
        assertTrue(
            "and success is measured, not assumed",
            owner.contains("return !root.exists() && bytesOnDisk(context) == 0L"),
        )
    }

    @Test
    fun whatRemoveFreesIsWhatTheCardCounted() {
        // The first version counted the whole tree and deleted only the top level, so a hand-made
        // subfolder would have been counted and then left behind, and the number would not have moved.
        assertTrue("the size walks the tree", owner.contains("ModelFootprint.bytesUnder(directory(context))"))
        assertTrue("and so does the removal", owner.contains("Files.walkFileTree("))
        assertTrue(
            "a removal that leaves anything must report itself as incomplete",
            screen.contains("lastRemovalFailed = !removed"),
        )
        assertTrue(
            "and say so where the number is",
            screen.contains("The last attempt to remove it did not finish."),
        )
    }

    @Test
    fun theHeavyWorkIsDeclaredAsBelongingOffTheMainThread() {
        // Hashing 469 MB on the main thread is an ANR. The card does it inside withContext(IO); the
        // owner says so, so the next caller has to have read it.
        assertTrue(
            "the owner must say what it costs",
            owner.contains("never call it on the main thread"),
        )
        val card = screen.substringAfter("private fun DevelopmentModelCard()").substringBefore("private data class DevelopmentModelFacts")
        assertTrue("the measurement must run on IO", card.contains("withContext(Dispatchers.IO)"))
        assertTrue(
            "and so must the removal",
            card.contains("runCatching { DevelopmentPolishModel.delete(context) }.getOrDefault(false)"),
        )
    }

    @Test
    fun theCardClaimsNothingItCannotEstablish() {
        // Three sentences were tried and all three overreached: that polish was RUNNING from the file,
        // that a measured speed CAME FROM it, and that a named model was present and invalid. The last
        // settled it, because a folder holding some other file produced a confident sentence about a
        // model that was not there. The card describes a FOLDER and its SIZE, and nothing else.
        listOf(
            "is running from this file",
            "will pick this file",
            "not the app's",
            "does not match what the app expects",
            // Who created the files is not knowable from here either; another development tool could
            // write into this folder just as easily as a person could.
            "put here by hand",
        ).forEach { claim ->
            assertFalse("the card must not claim: $claim", screen.contains(claim))
        }
        assertTrue(
            "it names the scope it actually measured",
            screen.contains("Development models folder"),
        )
        assertTrue(
            "and the one thing it can check about a released build",
            screen.contains("A released build never selects a model from here."),
        )
    }

    @Test
    fun noSentenceIsShownBeforeItsCondition() {
        // Enumerating the STRINGS was not enough; each also has a STATE it may appear in, and that axis
        // is where the last one hid. A measurement that had not returned yet was indistinguishable from
        // one that failed, so the card reported a failure in the moment before the first answer arrived,
        // and again after every removal.
        assertTrue(
            "pending must be its own state, not folded into failure",
            screen.contains("produceState<Result<Long>?>(initialValue = null)"),
        )
        assertTrue(
            "and nothing renders until there is an answer",
            screen.contains("if (measurement == null && !lastRemovalFailed) return") &&
                screen.contains("if (measurement != null) {"),
        )
    }

    @Test
    fun aFolderThatCannotBeMeasuredStillExplainsItself() {
        // A removal that failed must be able to say so even when the next measurement of that same
        // broken folder also fails. Tying the card's presence to a successful measurement hid both.
        assertTrue(
            "a failed measurement is its own answer",
            screen.contains("Could not measure what is in it"),
        )
        assertTrue(
            "and the card survives it when a removal failed",
            screen.contains("if (bytes == 0L && !lastRemovalFailed) return"),
        )
        assertTrue(
            "the failure describes the attempt, not the contents",
            screen.contains("The last attempt to remove it did not finish."),
        )
        assertFalse(
            "and it must not survive a recreation, or it describes replaced contents",
            screen.contains("var lastRemovalFailed by rememberSaveable"),
        )
    }
}
