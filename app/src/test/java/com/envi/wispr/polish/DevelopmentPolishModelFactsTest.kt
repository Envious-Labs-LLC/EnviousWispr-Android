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
                selector.contains("DevelopmentPolishModel.qualifies(context)"),
        )
    }

    @Test
    fun theTwoQuestionsAboutTheSameFileStaySeparate() {
        // EXISTS is what the screen asks, QUALIFIES is what polish asks. Collapsing them hides the case
        // that matters most: a large file with the wrong hash, which is doing nothing and costing
        // everything.
        assertTrue("presence must be measurable on its own", owner.contains("fun bytesOnDisk("))
        assertTrue("and loadability separately", owner.contains("fun qualifies("))
        assertTrue(
            "the card must decide on SIZE, not on whether the file is usable",
            screen.contains("if (facts.bytes <= 0L) return"),
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
            screen.contains("removalIncomplete = !removed"),
        )
        assertTrue(
            "and say so where the number is",
            screen.contains("Some of it could not be removed."),
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
    fun theCardClaimsEligibilityAndNeverLiveState() {
        // Nothing here can see what the :polish process has loaded. An earlier version said "polish is
        // running from this file", which this check cannot know and which is wrong for the whole window
        // before the first polish request.
        assertFalse("no claim about what is running", screen.contains("is running from this file"))
        assertTrue("the wording must be about what would be picked", screen.contains("will pick this file"))
        assertTrue(
            "and it must still warn that a measurement here is not the app's",
            screen.contains("not the app's"),
        )
        assertTrue(
            "the owner must say the same thing where the next reader looks",
            owner.contains("This is not a claim about what is loaded."),
        )
    }

    @Test
    fun aFailedCheckStillShowsWhatTheFileCosts() {
        // Measuring a directory succeeds where opening a file to hash it throws. Sharing one catch
        // threw the size away too and hid the card, so the user was told nothing about space they were
        // definitely paying for and could still have freed.
        assertTrue(
            "size and eligibility must be caught separately",
            screen.contains("runCatching { DevelopmentPolishModel.bytesOnDisk(context) }.getOrNull()") &&
                screen.contains("runCatching { DevelopmentPolishModel.qualifies(context) }.getOrNull()"),
        )
        assertTrue(
            "an unknown eligibility is its own answer, not a false",
            screen.contains("val selectable: Boolean?"),
        )
        assertTrue(
            "and the card must have a sentence for it",
            screen.contains("could not be checked"),
        )
    }
}
