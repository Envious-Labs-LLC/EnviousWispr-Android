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
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaSetter

/**
 * Drift Guard (#261, #275): a component Android constructs by class name must stay public
 * (`scripts/visibility-allowlist.txt`), but the members it declares for the app's own use must not be public Kotlin
 * API (`architecture-rules.md` RULE: minimize-visibility). `scripts/check-visibility.py` reads top-level declarations
 * only, so this reads the COMPILED classes: every public JVM method and field of each component, of its companion and
 * of its other nested objects, judged by the Kotlin visibility its class metadata records (a companion `const val` is
 * a public static field whatever it is declared, and an `internal` function a public method with a module-tagged
 * name). A method that overrides a supertype's instance method is the component's contract and passes. When this
 * fails, mark the named member `internal` (or `private`).
 */
class ComponentMemberVisibilityTest {
    private val components: List<Class<*>> = listOf(
        AsrService::class.java, AudioCaptureService::class.java, ModelBootstrapApplication::class.java,
        ModelDeliveryCancelReceiver::class.java, ModelDeliveryWorker::class.java, PasteAccessibilityService::class.java,
        PolishService::class.java, DictationTileService::class.java, AccessibilityGuideActivity::class.java,
        DictationSessionService::class.java, SettingsActivity::class.java, VoiceInputActivity::class.java,
        SilenceVadService::class.java,
    )

    /** Every superclass and interface up the chain, so an override of a grandparent's method is recognised. */
    private fun supertypesOf(type: Class<*>): List<Class<*>> {
        val out = mutableListOf<Class<*>>()
        val queue = ArrayDeque<Class<*>>().apply { add(type) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            listOfNotNull(current.superclass).plus(current.interfaces).forEach { if (it !in out) { out += it; queue += it } }
        }
        return out
    }

    /** An instance method a supertype also declares as an instance method, with a compatible return type. */
    private fun Method.overridesIn(supertypes: List<Class<*>>) = !Modifier.isStatic(modifiers) &&
        supertypes.any { parent ->
            runCatching { parent.getMethod(name, *parameterTypes) }.getOrNull()
                ?.let { !Modifier.isStatic(it.modifiers) && it.returnType.isAssignableFrom(returnType) } == true
        }

    /** The Kotlin visibility of the declaration behind [method], looked up in each Kotlin class that may declare it. */
    private fun visibilityOf(method: Method, owners: List<KClass<*>>): KVisibility? = owners.firstNotNullOfOrNull { owner ->
        owner.declaredMemberFunctions.firstOrNull { it.javaMethod == method }?.visibility
            ?: owner.declaredMemberProperties.firstNotNullOfOrNull { property ->
                when (method) {
                    property.javaGetter -> property.getter.visibility
                    (property as? KMutableProperty<*>)?.javaSetter -> property.setter.visibility
                    else -> null
                }
            }
    }

    private fun visibilityOf(field: Field, owners: List<KClass<*>>): KVisibility? = owners.firstNotNullOfOrNull { owner ->
        owner.declaredMemberProperties.firstOrNull { it.javaField == field }?.visibility
    }

    /** Public JVM members of [type] whose Kotlin declaration is public, or that have no Kotlin declaration at all. */
    private fun exposedIn(type: Class<*>, label: String, owners: List<KClass<*>>): List<String> {
        val supertypes = supertypesOf(type)
        val methods = type.declaredMethods.filter { method ->
            Modifier.isPublic(method.modifiers) && !method.isSynthetic && !method.isBridge && !method.overridesIn(supertypes) &&
                (visibilityOf(method, owners) ?: KVisibility.PUBLIC) == KVisibility.PUBLIC
        }.map { "$label.${it.name}" }
        val fields = type.declaredFields.filter { field ->
            // The companion and object instance handles are the object's own reference, judged by its members; a `$`
            // name (the Compose compiler's `$stable`) is compiler-generated, never a declaration.
            Modifier.isPublic(field.modifiers) && !field.isSynthetic && field.name != "Companion" && field.name != "INSTANCE" &&
                !field.name.startsWith("$") &&
                (visibilityOf(field, owners) ?: KVisibility.PUBLIC) == KVisibility.PUBLIC
        }.map { "$label.${it.name} (field)" }
        return methods + fields
    }

    /** MUTATIONS: a method, a companion constant, an instance `@JvmField` and a nested object's member put back to public. */
    @Test fun componentMembersAreNarrowerThanTheirComponent() {
        val exposed = components.flatMap { type ->
            val nested = type.declaredClasses.filter { it.declaredFields.any { field -> field.name == "INSTANCE" } || it.simpleName == "Companion" }
            // A component's static fields and @JvmStatic methods belong to its companion's properties and functions.
            val companion = type.declaredClasses.singleOrNull { it.simpleName == "Companion" }?.kotlin
            exposedIn(type, type.simpleName, listOfNotNull(type.kotlin, companion)) +
                nested.flatMap { exposedIn(it, "${type.simpleName}.${it.simpleName}", listOf(it.kotlin)) }
        }.distinct().sorted()
        assertEquals("members reachable from outside the app", emptyList<String>(), exposed)
    }

    /** The list is the allowlist, row for row: a component added there is judged here without a second edit. */
    @Test fun theComponentsAreExactlyTheAllowlist() {
        val rows = File("../scripts/visibility-allowlist.txt").readLines()
            .map { it.substringBefore("#").trim() }.filter { it.isNotEmpty() }
            .map { row ->
                val path = row.substringBeforeLast(":")
                val pkg = File("../$path").readLines().first { it.startsWith("package ") }.removePrefix("package ").trim()
                "$pkg.${row.substringAfterLast(":")}"
            }
        assertEquals(rows.sorted(), components.map { it.name }.sorted())
    }
}
