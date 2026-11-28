package com.envi.wispr.snippets

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal val Context.snippetStore by dataStore("snippets.json", SnippetSerializer)

internal object SnippetSerializer : Serializer<SnippetLibrary> {
    override val defaultValue: SnippetLibrary by lazy { SnippetLibrary(snippets = starterSnippets()) }

    override suspend fun readFrom(input: InputStream): SnippetLibrary = try {
        decode(input.bufferedReader(Charsets.UTF_8).readText())
    } catch (error: IllegalArgumentException) {
        throw CorruptionException("Cannot read this snippet library", error)
    } catch (error: org.json.JSONException) {
        throw CorruptionException("Cannot read this snippet library", error)
    }

    override suspend fun writeTo(t: SnippetLibrary, output: OutputStream) {
        output.write(encode(t).toByteArray(Charsets.UTF_8))
    }

    fun encode(library: SnippetLibrary): String {
        val entries = JSONArray()
        library.snippets.forEach { item ->
            entries.put(JSONObject().put("id", item.id).put("trigger", item.trigger)
                .put("expansion", item.expansion).put("createdAt", item.createdAt))
        }
        return JSONObject().put("version", 1).put("keyword", library.keyword).put("snippets", entries).toString(2)
    }

    fun decode(source: String): SnippetLibrary {
        val document = JSONObject(source)
        require(document.get("version") == 1) { "This snippet file needs another version of EnviousWispr." }
        val entries = document.getJSONArray("snippets")
        val snippets = List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val id = item.get("id") as? String
            require(id != null && UUID.fromString(id).toString().equals(id, ignoreCase = true)) { "Invalid snippet ID." }
            val trigger = item.get("trigger") as? String
            val expansion = item.get("expansion") as? String
            val created = item.get("createdAt") as? Number
            require(trigger != null && expansion != null && created != null && created.toDouble().isFinite()) { "Invalid snippet file." }
            Snippet(id, trigger, expansion, created.toDouble())
        }
        val keyword = document.get("keyword") as? String
        require(keyword != null) { "Invalid snippet keyword." }
        return SnippetLibrary(keyword, snippets)
    }
}

internal fun newSnippet(trigger: String, expansion: String) = Snippet(
    UUID.randomUUID().toString(), trigger.trim(), expansion,
    System.currentTimeMillis() / 1000.0 - 978_307_200.0, // Mac JSON dates use January 1, 2001.
)

internal fun SnippetLibrary.save(base: Snippet?, next: Snippet): SnippetLibrary {
    require(next.words.isNotEmpty()) { "Enter the words you want to say." }
    require(next.expansion.isNotBlank()) { "Enter the text to insert." }
    require(snippets.find { it.id == next.id } == base) { "This snippet changed. Open it again." }
    require(snippets.none { it.id != next.id && it.words == next.words }) { "That trigger is already in use." }
    return copy(snippets = if (base == null) listOf(next) + snippets else snippets.map { if (it.id == next.id) next else it })
        .also(SnippetTransfer::validateLibrary)
}

internal fun SnippetLibrary.withKeyword(input: String): SnippetLibrary {
    val key = normalize(input)
    require(key.none { it.isWhitespace() }) { "Choose a single word for the command." }
    return copy(keyword = key.ifEmpty { "backslash" })
}
