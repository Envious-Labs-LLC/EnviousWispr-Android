package com.envi.wispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drift Guard on the shape #191 left (`theTreeHasNoPublicDefaultOutsideTheAllowlist`): when it fails, a
 * public default or an `else` over a closed set reached `app/src/main`, which the user feels the next time
 * a state is added and silently takes an old branch. The fixture rows are Harness Contract: they pin
 * `scripts/check-visibility.py` in BOTH directions, one rejecting fixture per rule and per scanner state,
 * and one open `when` that must pass, so a check that stopped seeing would go red here before it stopped
 * guarding.
 *
 * The script runs as a process: exit code EXACTLY 0 or 1 is the assertion, a process that cannot launch
 * or exits 2 is a failure, never a pass. `:app:testDebugUnitTest` runs with `app/` as the working
 * directory, so the repository root is its parent and the fixtures are under `src/test/resources`.
 */
class VisibilityCheckTest {

    private val repo = File("..").canonicalFile
    private val script = File(repo, "scripts/check-visibility.py")
    private val fixtures = File("src/test/resources/visibility").canonicalFile

    private class Run(val exit: Int, val out: String)

    private fun run(vararg args: String): Run {
        assertTrue("the check must exist at ${script.path}", script.isFile)
        val process = ProcessBuilder(listOf("python3", script.path) + args)
            .directory(repo)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("the check must finish", process.waitFor(120, TimeUnit.SECONDS))
        val exit = process.exitValue()
        assertTrue("exit 2 is a usage or I/O error, never a verdict:\n$out", exit != 2)
        return Run(exit, out)
    }

    @Test
    fun theTreeHasNoPublicDefaultOutsideTheAllowlist() {
        val r = run()
        assertEquals("the shipped tree must be clean:\n${r.out}", 0, r.exit)
        assertTrue(r.out.startsWith("clean:"))
    }

    private fun rejected(case: String, expectedHit: String) {
        val r = run("--root", File(fixtures, case).path)
        assertEquals("$case must be refused with exit 1:\n${r.out}", 1, r.exit)
        val hits = r.out.lines().filter { ": public-default" in it || ": else over" in it }
        assertEquals("$case must produce exactly one hit:\n${r.out}", 1, hits.size)
        assertTrue("$case must name the line and the reason, got: ${hits.single()}", hits.single().startsWith(expectedHit))
    }

    @Test
    fun aStagedPublicClassIsRefused() {
        rejected("public-class", "app/src/main/java/com/envi/wispr/Leak.kt:3: public-default top-level class Leak")
        rejected("name-next-line", "app/src/main/java/com/envi/wispr/Leak.kt:3: public-default top-level class Leak")
        rejected("indented-top-level", "app/src/main/java/com/envi/wispr/Leak.kt:6: public-default top-level class Leak")
        rejected("allowlisted-name-wrong-file", "app/src/main/java/com/envi/wispr/Other.kt:3: public-default top-level class SettingsActivity")
        // Code review round 1: an explicit `public` is the default written out; a same-line annotation
        // and `fun interface` are still declarations.
        rejected("explicit-public", "app/src/main/java/com/envi/wispr/Leak.kt:3: public-default top-level class Leak")
        rejected("annotated-same-line", "app/src/main/java/com/envi/wispr/Leak.kt:3: public-default top-level fun leak")
        rejected("fun-interface", "app/src/main/java/com/envi/wispr/Leak.kt:3: public-default top-level interface Leak")
    }

    @Test
    fun everyScannerStateStillSeesTheTopLevelAfterIt() {
        // The six Kotlin lexical states the scanner names (plan §3.3): a brace hidden in each must not
        // move the depth, so the indented public class after it is still top-level.
        rejected("after-line-comment", "app/src/main/java/com/envi/wispr/Leak.kt:7: public-default top-level class Leak")
        rejected("after-block-comment", "app/src/main/java/com/envi/wispr/Leak.kt:7: public-default top-level class Leak")
        rejected("after-escaped-string", "app/src/main/java/com/envi/wispr/Leak.kt:6: public-default top-level class Leak")
        rejected("after-nested-template", "app/src/main/java/com/envi/wispr/Leak.kt:6: public-default top-level class Leak")
        rejected("after-raw-string", "app/src/main/java/com/envi/wispr/Leak.kt:6: public-default top-level class Leak")
        rejected("after-char-literal", "app/src/main/java/com/envi/wispr/Leak.kt:7: public-default top-level class Leak")
    }

    @Test
    fun aStagedElseOverAnEnumIsRefused() {
        rejected("else-over-enum", "app/src/main/java/com/envi/wispr/Color.kt:8: else over the closed set Color")
        rejected("else-over-sealed-data-object", "app/src/main/java/com/envi/wispr/Shape.kt:11: else over the closed set Shape")
        rejected("else-over-nested-enum-bare", "app/src/main/java/com/envi/wispr/Owner.kt:8: else over the closed set Mode")
        // Code review round 1: a sealed child declared in another file of the package, and an enum whose
        // entries are lowercase or backticked with a `;` body.
        rejected("sealed-child-in-other-file", "app/src/main/java/com/envi/wispr/Box.kt:8: else over the closed set Shape")
        rejected("lowercase-enum-with-body", "app/src/main/java/com/envi/wispr/Tone.kt:14: else over the closed set Tone")
    }

    @Test
    fun anOpenWhenWithAnElseIsNotTheChecksBusiness() {
        val r = run("--root", File(fixtures, "open-mixed-when").path)
        assertEquals("a when over an Int, a guarded arm, or a mix of a member and a type test must pass:\n${r.out}", 0, r.exit)
        // An open subject whose arms happen to name members keeps its else with a stated reason on the
        // when's own line (`// visibility-open-when: <reason>`).
        val marked = run("--root", File(fixtures, "open-when-marker").path)
        assertEquals("the marker with a reason must let the else stand:\n${marked.out}", 0, marked.exit)
        // Code review round 2: the marker counts only inside a real comment, and a simple name that is a
        // closed set in two packages is ambiguous, so its when stays open to the check.
        rejected("open-when-marker-in-string", "app/src/main/java/com/envi/wispr/Any.kt:7: else over the closed set Color")
        val collision = run("--root", File(fixtures, "cross-package-name-collision").path)
        assertEquals("two sealed Shapes in two packages are ambiguous, so the else stands:\n${collision.out}", 0, collision.exit)
        assertTrue(collision.out.contains("0 closed sets known"))
    }
}
