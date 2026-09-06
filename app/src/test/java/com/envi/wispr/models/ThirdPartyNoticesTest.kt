package com.envi.wispr.models

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Product Outcome, and an unusual one: the user this protects is a Play reviewer and anyone auditing
 * what the app ships. When it fails, a licence document states something false about software we
 * redistribute, which is a policy problem rather than a stale comment.
 *
 * **These are the exact defects that were found (#15).** The shipped asset named S1-mini at
 * `ee2c0f56…` while `ModelManifest` pinned `34add00a…`, and it called llama.cpp Apache-2.0 while
 * `third_party/llama.cpp/LICENSE` says MIT. Nothing linked either claim to the thing it described, so
 * both went stale in silence (`workflow-process.md` RULE: prose-carries-the-same-evidence-burden-as-code).
 *
 * Both notices files are plain text on disk, so a JVM test reads them without a device.
 */
class ThirdPartyNoticesTest {

    private val shipped = File("src/main/assets/THIRD_PARTY_NOTICES.txt").readText()
    private val root = File("../THIRD-PARTY-NOTICES.txt")
    private val gradleFile = File("build.gradle.kts").readText()

    // ---- The notices shipped inside the app ----

    @Test
    fun everyModelTheAppShipsIsNamedWithTheRevisionItActuallyPins() {
        listOf(ModelManifest.parakeet, ModelManifest.s1).forEach { model ->
            assertTrue(
                "the notices must pin ${model.id} at ${model.pinnedRevision}, which the app uses",
                shipped.contains(model.pinnedRevision),
            )
        }
    }

    @Test
    fun theNoticesDoNotStillNameARevisionTheAppAbandoned() {
        // The failing shape is not an absent revision, it is a SECOND one left behind beside the right
        // one. Anything that looks like a full commit hash has to be a revision something still pins.
        val known = setOf(
            ModelManifest.parakeet.pinnedRevision,
            ModelManifest.s1.pinnedRevision,
            SUBMODULE_LLAMA_CPP,
        )
        val named = Regex("Pinned revision: ([0-9a-f]{40})").findAll(shipped).map { it.groupValues[1] }.toList()
        assertTrue("the notices must pin something", named.isNotEmpty())
        val strangers = named.filterNot { it in known }
        assertTrue(
            "the notices name revisions nothing in this app pins: $strangers",
            strangers.isEmpty(),
        )
    }

    @Test
    fun everyModelsLicenceIsNamedSomewhereInTheNotices() {
        listOf(ModelManifest.parakeet, ModelManifest.s1).forEach { model ->
            assertTrue(
                "${model.id} is licensed ${model.license} and the notices must say so",
                shipped.contains(model.license) ||
                    // Apache is spelled out in full further down rather than by its short name.
                    (model.license.startsWith("Apache") && shipped.contains("Apache License")),
            )
        }
    }

    @Test
    fun theLicenceClaimedForLlamaCppIsTheOneLlamaCppDeclares() {
        // The oracle is llama.cpp's own LICENSE file, which neither notices file wrote. Comparing the
        // claim to another sentence we wrote would only prove our two sentences agree.
        val declared = File("../third_party/llama.cpp/LICENSE")
        assumeCheckoutHasSubmodule(declared)
        val declaredName = declared.readLines().first { it.isNotBlank() }.trim()
        val paragraphs = shipped.split("\n\n")
            .filter { it.contains("llama.cpp") && it.contains("License") }
        assertTrue(
            "the shipped notices must say under which licence llama.cpp is distributed",
            paragraphs.isNotEmpty(),
        )
        paragraphs.forEach { paragraph ->
            assertTrue(
                "llama.cpp's own licence file says '$declaredName', so this must not say otherwise: $paragraph",
                paragraph.contains(declaredName),
            )
        }
    }

    // ---- The notices at the repository root ----

    @Test
    fun theRootNoticesFileExists() {
        assertTrue(
            "THIRD-PARTY-NOTICES.txt must exist at the repository root; regenerate it with " +
                "scripts/generate-notices.py",
            root.isFile(),
        )
    }

    @Test
    fun everyLibraryTheAppShipsIsNamedInTheRootNotices() {
        // A dependency added without regenerating the file is the way this goes stale, and it is
        // invisible otherwise. Test-only and debug-only dependencies are excluded because they are not
        // in the release APK and so are not redistributed.
        val text = root.readText()
        val declared = Regex("""(?:^|\n)\s*implementation\((?:platform\()?"([^"]+:[^"]+)"""")
            .findAll(gradleFile)
            // The version may be absent, supplied by the Compose bill of materials, so match on the
            // group and artifact only. The notices file carries the version that actually resolved.
            .map { it.groupValues[1].split(':').take(2).joinToString(":") }
            .distinct()
            .toList()
        assertTrue("the build file must declare dependencies", declared.isNotEmpty())

        // What the notices list is what RESOLVED, and a Compose Multiplatform library resolves to its
        // per-platform artifact: `androidx.compose.material3:material3` arrives as `material3-android`.
        // Requiring the declared name verbatim would report those as uncovered when they are listed.
        val present = Regex("""(?:^|\n)\s{2}([^\s:]+:[^\s:]+):""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
        val missing = declared.filterNot { coordinate ->
            coordinate in present ||
                listOf("-android", "-jvm").any { suffix -> "$coordinate$suffix" in present }
        }
        assertTrue(
            "these dependencies are not in the root notices; rerun scripts/generate-notices.py: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun theRootNoticesNameEveryComponentThatHasNoMavenCoordinate() {
        // These three ship inside the APK without a Maven coordinate, so nothing in the dependency
        // listing can account for them and only a named entry can.
        val text = root.readText()
        listOf("sherpa-onnx", "ONNX Runtime", "llama.cpp").forEach { component ->
            assertTrue("the root notices must name $component", text.contains(component))
        }
        assertTrue(
            "the root notices must pin llama.cpp at the commit this checkout builds",
            text.contains(SUBMODULE_LLAMA_CPP),
        )
    }

    private fun assumeCheckoutHasSubmodule(license: File) {
        // A clone without submodules initialised has no file to read, and a red row there would accuse
        // correct code (`validation-discipline.md`: a red row for a scenario the harness cannot stage
        // is worse than a skip).
        org.junit.Assume.assumeTrue(
            "third_party/llama.cpp is not checked out in this clone",
            license.isFile(),
        )
    }

    private companion object {
        /**
         * The llama.cpp commit this checkout pins, read from `git submodule status` on 2026-09-06.
         *
         * Hard-coded rather than shelled out, because a unit test that runs git is testing git. If the
         * submodule moves and the notices are updated to match, this constant is what goes red, which
         * is the reminder to check the rest of the entry too.
         */
        const val SUBMODULE_LLAMA_CPP = "ca3d5a3e10d53f7ea672cb9b6178faca3e2807bc"
    }
}
