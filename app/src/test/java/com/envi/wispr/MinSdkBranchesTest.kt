package com.envi.wispr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift Guard (#260): no `Build.VERSION.SDK_INT` comparison under the shipping app's source may be one that
 * `minSdk` already decides. Such a branch is code no installable phone can run (the pre-API-31 vibrator
 * fallback, a notification check for Android 12, an Android 10 tile subtitle). When this fails, a check for
 * a version the app never installs on came back; delete the branch it guards instead.
 */
class MinSdkBranchesTest {
    private val minSdk = Regex("""\bminSdk\s*=\s*(\d+)""").find(File("build.gradle.kts").readText())
        ?.groupValues?.get(1)?.toInt() ?: error("minSdk not found in app/build.gradle.kts")

    private val codes = mapOf(
        "Q" to 29, "R" to 30, "S" to 31, "S_V2" to 32, "TIRAMISU" to 33,
        "UPSIDE_DOWN_CAKE" to 34, "VANILLA_ICE_CREAM" to 35, "BAKLAVA" to 36,
    )

    /** `SDK_INT <op> N` is fixed on every phone at or above [floor]. */
    private fun decided(op: String, n: Int, floor: Int): Boolean = when (op) {
        ">=", "<" -> n <= floor
        ">", "<=", "==", "!=" -> n < floor
        else -> error("unknown operator $op")
    }

    /** Any spelling of the level: a number, `VERSION_CODES.X`, `Build.VERSION_CODES.X`, `android.os.Build.VERSION_CODES.X`. */
    private val comparison = Regex("""SDK_INT\s*(>=|<=|==|!=|>|<)\s*(\d+|(?:[\w.]+\.)?VERSION_CODES\.(\w+))""")

    /** The rule at the floor, both ways (coverage round, finding 1). */
    @Test fun theRuleDecidesExactlyTheComparisonsTheFloorFixes() {
        val floor = 33
        assertTrue(">= 33 is always true", decided(">=", 33, floor))
        assertTrue("< 33 is always false", decided("<", 33, floor))
        assertFalse("> 33 differs between 33 and 34", decided(">", 33, floor))
        assertFalse("<= 33 differs between 33 and 34", decided("<=", 33, floor))
        assertFalse("== 33 differs between 33 and 34", decided("==", 33, floor))
        assertTrue("== 32 is always false", decided("==", 32, floor))
        assertFalse(">= 34 is live", decided(">=", 34, floor))
        assertTrue("!= 31 is always true", decided("!=", 31, floor))
    }

    /** MUTATION: put `SDK_INT >= Build.VERSION_CODES.S` back in `EnviousWisprTheme`. */
    @Test fun noComparisonInTheAppIsOneMinSdkDecides() {
        assertEquals("the shipping floor", 33, minSdk)
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the production tree must be readable", sources.size > 100)
        val dead = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                comparison.find(line)?.let { match ->
                    val (op, value, name) = match.destructured
                    val n = if (name.isEmpty()) value.toInt() else codes[name] ?: error("unmapped VERSION_CODES.$name at ${file.name}:${index + 1}")
                    if (decided(op, n, minSdk)) "${file.name}:${index + 1} SDK_INT $op $n" else null
                }
            }
        }
        assertEquals("comparisons minSdk $minSdk already decides", emptyList<String>(), dead)
    }
}
