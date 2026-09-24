package com.envi.wispr.paste

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Drift Guard (#261): a component Android constructs by class name must stay public
 * (`scripts/visibility-allowlist.txt`), but the members it declares for the app's own use must not.
 * `scripts/check-visibility.py` reads top-level declarations only, so this reads the COMPILED class: Kotlin
 * gives an `internal` member a public JVM name ending in the module tag (`$app_debug`), and anything else
 * public that overrides nothing is a member the whole device could reach. When this fails, mark the named
 * member `internal` (or `private`).
 *
 * The list is data; the follow-up for the other allowlisted components extends it.
 */
class ComponentMemberVisibilityTest {
    private val components = listOf(PasteAccessibilityService::class.java)

    private val moduleTag = Regex("\\\$app_\\w+$")

    private fun Method.isExposed() =
        Modifier.isPublic(modifiers) && !isSynthetic && !isBridge && !moduleTag.containsMatchIn(name)

    private fun Method.overridesIn(supertypes: List<Class<*>>) = !Modifier.isStatic(modifiers) &&
        supertypes.any { parent -> runCatching { parent.getMethod(name, *parameterTypes) }.isSuccess }

    /** MUTATION: put `windowTreeXml` or `startDictationFromBubble` back to public. */
    @Test fun componentMembersAreNarrowerThanTheirComponent() {
        val exposed = components.flatMap { type ->
            val supertypes = listOfNotNull(type.superclass) + type.interfaces
            val companion = type.declaredClasses.single { it.simpleName == "Companion" }
            type.declaredMethods.filter { it.isExposed() && !it.overridesIn(supertypes) }.map { "${type.simpleName}.${it.name}" } +
                companion.declaredMethods.filter { it.isExposed() }.map { "${type.simpleName}.Companion.${it.name}" }
        }.sorted()
        assertEquals("members reachable from outside the app", emptyList<String>(), exposed)
    }
}
