package com.envi.wispr.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome, and an unusual one: the user this protects is a Play reviewer and anyone auditing
 * what the app ships. When it fails, a licence document states something false about software we
 * redistribute, which is a policy problem rather than a stale comment.
 *
 * **These are the exact defects that were found (#15).** The shipped asset named S1-mini at
 * `ee2c0f56…` while `ModelManifest` pinned `34add00a…`, and it called llama.cpp Apache-2.0 while
 * llama.cpp's own LICENSE says MIT. Nothing linked either claim to the thing it described.
 *
 * **Every assertion here compares a claim to a source this test did not write** — `ModelManifest`,
 * llama.cpp's own licence file, the dependency list a human typed into `build.gradle.kts`. Asserting
 * that a string appears SOMEWHERE in a notices file proves only that the string is present, not that
 * it is attached to the right component, so each check parses the entry it is about.
 *
 * Both notices files are plain text on disk, so a JVM test reads them without a device.
 */
class ThirdPartyNoticesTest {

    private val shipped = File("src/main/assets/THIRD_PARTY_NOTICES.txt").readText()
    private val rootFile = File("../THIRD-PARTY-NOTICES.txt")
    private val gradleFile = File("build.gradle.kts").readText()

    /** One blank-line-separated entry of the shipped asset, as its `Field: value` pairs. */
    private fun shippedEntries(): List<Map<String, String>> =
        shipped.split("\n\n").map { block ->
            block.lineSequence()
                .mapNotNull { line ->
                    val separator = line.indexOf(": ")
                    if (separator <= 0 || line.first().isWhitespace()) null
                    else line.substring(0, separator) to line.substring(separator + 2).trim()
                }
                .toMap()
        }

    // ---- The notices shipped inside the app ----

    @Test
    fun eachModelEntryCarriesTheRevisionAndLicenceThatModelActuallyHas() {
        val entries = shippedEntries().filter { it.containsKey("Model ID") }
        assertEquals(
            "the notices must carry exactly one entry per model the app ships",
            ModelManifest.all.map { it.id }.sorted(),
            entries.mapNotNull { it["Model ID"] }.sorted(),
        )
        ModelManifest.all.forEach { model ->
            val entry = entries.single { it["Model ID"] == model.id }
            assertEquals(
                "the ${model.id} entry must pin the revision the app uses",
                model.pinnedRevision,
                entry["Pinned revision"],
            )
            assertEquals(
                "the ${model.id} entry must name the licence the app records for it",
                model.license,
                entry["License"],
            )
        }
    }

    @Test
    fun theNoticesDoNotStillNameARevisionNothingPins() {
        // The failing shape is not an absent revision, it is a SECOND one left behind beside the right
        // one. Anything shaped like a full commit hash has to be a revision something still pins.
        val known = ModelManifest.all.map { it.pinnedRevision }.toSet() + SUBMODULE_LLAMA_CPP
        listOf("the shipped notices" to shipped, "the root notices" to rootFile.readText())
            .forEach { (label, text) ->
                val named = Regex("[0-9a-f]{40}").findAll(text).map { it.value }.toSet()
                assertTrue("$label must pin something", named.isNotEmpty())
                assertTrue(
                    "$label names revisions nothing in this app pins: ${named - known}",
                    (named - known).isEmpty(),
                )
            }
    }

    @Test
    fun theLicenceClaimedForLlamaCppIsTheOneLlamaCppDeclares() {
        // The oracle is llama.cpp's own licence file. The committed copy under scripts/license-texts is
        // read first so this runs in a clone without submodules; the submodule itself is then compared
        // to that copy when it is present, which is what catches the copy going stale.
        val committed = File("../scripts/license-texts/MIT-llama.cpp.txt")
        assertTrue("the committed copy of llama.cpp's licence must exist", committed.isFile())
        val declared = committed.readLines().first { it.isNotBlank() }.trim()

        listOf("the shipped notices" to shipped, "the root notices" to rootFile.readText())
            .forEach { (label, text) ->
                val entries = text.split("\n\n")
                    .filter { it.lineSequence().any { line -> line.trim().startsWith("llama.cpp") } }
                    .map { block ->
                        block.lineSequence()
                            .mapNotNull { line ->
                                val trimmed = line.trim()
                                if (!trimmed.startsWith("License: ")) null
                                else trimmed.removePrefix("License: ").trim()
                            }
                            .toList()
                    }
                    .filter { it.isNotEmpty() }
                assertEquals("$label must make exactly one licence claim about llama.cpp", 1, entries.size)
                assertEquals(
                    "$label must not make two licence claims about llama.cpp",
                    1,
                    entries.single().size,
                )
                assertEquals(
                    "llama.cpp's own licence file says '$declared', so $label must say the same",
                    declared,
                    entries.single().single(),
                )
            }
    }

    @Test
    fun theCommittedCopyOfLlamaCppsLicenceStillMatchesTheSubmodule() {
        val submodule = File("../third_party/llama.cpp/LICENSE")
        // A clone without submodules initialised has no file to read, and a red row there would accuse
        // correct code (`validation-discipline.md`: a red row for a scenario the harness cannot stage
        // is worse than a skip). The check above still runs against the committed copy.
        assumeTrue("third_party/llama.cpp is not checked out in this clone", submodule.isFile())
        assertEquals(
            "scripts/license-texts/MIT-llama.cpp.txt has drifted from llama.cpp's own LICENSE",
            submodule.readText(),
            File("../scripts/license-texts/MIT-llama.cpp.txt").readText(),
        )
    }

    // ---- The notices at the repository root ----

    @Test
    fun theRootNoticesFileExists() {
        assertTrue(
            "THIRD-PARTY-NOTICES.txt must exist at the repository root; regenerate it with " +
                "scripts/generate-notices.py",
            rootFile.isFile(),
        )
    }

    @Test
    fun everyLibraryTheAppDeclaresIsListedWithAVersionAndALicence() {
        // A dependency added without regenerating the file is how this goes stale, and nothing else
        // would show it. Test-only and debug-only dependencies are excluded: they are not in the
        // release APK, so they are not redistributed.
        //
        // The match is EXACT on group and artifact. An earlier version of this accepted an `-android`
        // suffix as covering the plain artifact, which hid two Compose libraries that were genuinely
        // absent from the file, so a widened matcher here is how the guard stops guarding.
        val declared = Regex("""(?:^|\n)\s*implementation\((?:platform\()?"([^"]+:[^"]+)"""")
            .findAll(gradleFile)
            .map { it.groupValues[1].split(':').take(2).joinToString(":") }
            .distinct()
            .toList()
        assertTrue("the build file must declare dependencies", declared.isNotEmpty())

        val listed = mavenRows()
        assertTrue("the root notices must list dependencies", listed.isNotEmpty())
        val missing = declared.filterNot { listed.containsKey(it) }
        assertTrue(
            "these dependencies are not in the root notices; rerun scripts/generate-notices.py: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun noListedDependencyIsMissingItsVersionOrItsLicence() {
        // A row with a blank licence column reads like a row that was checked. It is what a partly
        // failed generation would leave behind, so it must not be able to pass silently.
        mavenRows().forEach { (module, row) ->
            val (version, licence) = row
            assertTrue("$module is listed with no version", version.isNotBlank())
            assertTrue("$module is listed with no licence", licence.isNotBlank())
            assertTrue(
                "$module is listed with a placeholder licence: $licence",
                !licence.contains("unknown", ignoreCase = true) && !licence.contains("TODO"),
            )
        }
    }

    @Test
    fun theRootNoticesNameEveryComponentThatHasNoMavenCoordinate() {
        // These three ship inside the APK without a Maven coordinate, so nothing in the dependency
        // listing can account for them and only a named entry can.
        val text = rootFile.readText()
        listOf("sherpa-onnx", "ONNX Runtime", "llama.cpp").forEach { component ->
            assertTrue("the root notices must name $component", text.contains(component))
        }
        assertTrue(
            "the root notices must pin llama.cpp at the commit this checkout builds",
            text.contains(SUBMODULE_LLAMA_CPP),
        )
    }

    /** Every `group:artifact:version  Licence` row of the root file, keyed by `group:artifact`. */
    private fun mavenRows(): Map<String, Pair<String, String>> =
        Regex("""(?m)^ {2}(\S+:\S+):(\S+) {2,}(.+)$""")
            .findAll(rootFile.readText())
            .associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3].trim()) }

    private companion object {
        /**
         * The llama.cpp commit this checkout pins, read from `git submodule status` on 2026-09-06.
         *
         * Hard-coded rather than shelled out, because a unit test that runs git is testing git. If the
         * submodule moves and the notices are regenerated, this constant is what goes red, which is the
         * reminder to check the rest of the entry too.
         */
        const val SUBMODULE_LLAMA_CPP = "ca3d5a3e10d53f7ea672cb9b6178faca3e2807bc"
    }
}
