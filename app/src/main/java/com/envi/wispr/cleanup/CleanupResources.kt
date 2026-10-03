package com.envi.wispr.cleanup

import java.io.InputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bundled, immutable small tables. Loaded on the existing processing worker, once per process. */
internal object CleanupResources {
    // The application installs its asset reader before any service can process text. JVM tests read the
    // SAME source assets through their resource classpath, not a second test-authored dictionary.
    @Volatile private var reader: (String) -> InputStream? = { name -> CleanupResources::class.java.getResourceAsStream("/cleanup/$name") }
    fun installAssetReader(read: (String) -> InputStream?) { reader = read }

    private fun <T> nullableLoad(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    data class Emoji(val phrase: String, val glyph: String, val synonyms: List<String>)
    val emoji: List<Emoji>? by lazy {
        nullableLoad {
            val text = checkNotNull(reader("emoji-dictionary.json")).bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonArray.map { element ->
                val row = element.jsonObject
                Emoji(row.getValue("phrase").jsonPrimitive.content, row.getValue("emoji").jsonPrimitive.content,
                    row["synonyms"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
            }.also { require(it.isNotEmpty() && it.all { e -> e.phrase.isNotBlank() && e.glyph.isNotBlank() }) }
        }
    }
    val british: Map<String, String>? by lazy {
        nullableLoad {
            val text = checkNotNull(reader("british-spelling.json")).bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
                .also { require(it.isNotEmpty() && it.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) }
        }
    }
}
