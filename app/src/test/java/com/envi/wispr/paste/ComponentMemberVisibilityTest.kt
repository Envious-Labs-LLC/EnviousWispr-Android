package com.envi.wispr.paste

import com.envi.wispr.asr.AsrService
import com.envi.wispr.audio.AudioCaptureService
import com.envi.wispr.models.ModelBootstrapApplication
import com.envi.wispr.models.ModelDeliveryCancelReceiver
import com.envi.wispr.models.ModelDeliveryWorker
import com.envi.wispr.polish.PolishService
import com.envi.wispr.shortcuts.DictationTileService
import com.envi.wispr.ui.AccessibilityGuideActivity
import com.envi.wispr.ui.DictationSessionService
import com.envi.wispr.ui.SettingsActivity
import com.envi.wispr.ui.VoiceInputActivity
import com.envi.wispr.vad.SilenceVadService
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KVisibility
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredMemberProperties

/**
 * Drift Guard (#261, #275): a component Android constructs by class name must stay public
 * (`scripts/visibility-allowlist.txt`), but the members it declares for the app's own use must not.
 * `scripts/check-visibility.py` reads top-level declarations only, so this reads the COMPILED classes: Kotlin
 * gives an `internal` member a public JVM name ending in the module tag (`$app_debug`), and anything else
 * public that overrides nothing is a member the whole device could reach. A companion `const val` compiles to a
 * public static field whatever its Kotlin visibility, so companion properties are judged by their Kotlin
 * visibility from the class metadata. When this fails, mark the named member `internal` (or `private`).
 */
class ComponentMemberVisibilityTest {
    private val components: List<Class<*>> = listOf(
        AsrService::class.java, AudioCaptureService::class.java, ModelBootstrapApplication::class.java,
        ModelDeliveryCancelReceiver::class.java, ModelDeliveryWorker::class.java, PasteAccessibilityService::class.java,
        PolishService::class.java, DictationTileService::class.java, AccessibilityGuideActivity::class.java,
        DictationSessionService::class.java, SettingsActivity::class.java, VoiceInputActivity::class.java,
        SilenceVadService::class.java,
    )

    private val moduleTag = Regex("\\\$app_\\w+$")

    private fun Method.isExposed() =
        Modifier.isPublic(modifiers) && !isSynthetic && !isBridge && !moduleTag.containsMatchIn(name)

    private fun Method.overridesIn(supertypes: List<Class<*>>) = !Modifier.isStatic(modifiers) &&
        supertypes.any { parent -> runCatching { parent.getMethod(name, *parameterTypes) }.isSuccess }

    /** Every superclass and interface up the chain, so an override of a grandparent's method is recognised. */
    private fun supertypesOf(type: Class<*>): List<Class<*>> {
        val out = mutableListOf<Class<*>>()
        var current: Class<*>? = type
        while (current != null) {
            current.superclass?.let(out::add)
            out += current.interfaces
            current = current.superclass
        }
        return out
    }

    /** MUTATIONS: put `windowTreeXml` back to public (a method); put `DictationSessionService.ACTION_TOGGLE` back to public (a constant). */
    @Test fun componentMembersAreNarrowerThanTheirComponent() {
        val exposed = components.flatMap { type ->
            val supertypes = supertypesOf(type)
            val companion = type.kotlin.companionObject
            type.declaredMethods.filter { it.isExposed() && !it.overridesIn(supertypes) }.map { "${type.simpleName}.${it.name}" } +
                (type.declaredClasses.singleOrNull { it.simpleName == "Companion" }?.declaredMethods.orEmpty()
                    .filter { it.isExposed() }.map { "${type.simpleName}.Companion.${it.name}" }) +
                (companion?.declaredMemberProperties.orEmpty()
                    .filter { it.visibility == KVisibility.PUBLIC }.map { "${type.simpleName}.Companion.${it.name} (property)" })
        }.distinct().sorted()
        assertEquals("members reachable from outside the app", emptyList<String>(), exposed)
    }

    /** The list is the allowlist: a component added there is judged here without a second edit. */
    @Test fun theComponentsAreExactlyTheAllowlist() {
        val allowlisted = File("../scripts/visibility-allowlist.txt").readLines()
            .map { it.substringBefore("#").trim() }.filter { it.isNotEmpty() }
            .map { it.substringAfterLast(":") }.toSet()
        assertEquals(allowlisted, components.map { it.simpleName }.toSet())
    }
}
