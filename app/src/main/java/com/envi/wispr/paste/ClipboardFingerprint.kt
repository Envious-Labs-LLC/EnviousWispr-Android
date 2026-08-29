package com.envi.wispr.paste

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle

private const val CLIP_OWNER_MIME_PREFIX = "application/vnd.enviouswispr.take-"

internal fun enviousWisprTextClip(text: String, token: String): ClipData = ClipData(
    ClipDescription(
        "EnviousWispr",
        arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN, CLIP_OWNER_MIME_PREFIX + token.lowercase()),
    ),
    ClipData.Item(text),
)

internal fun ClipData?.isOwnedBy(
    token: String,
    fingerprint: ClipboardFingerprint?,
): Boolean = this != null &&
    (0 until description.mimeTypeCount)
        .map(description::getMimeType)
        .contains(CLIP_OWNER_MIME_PREFIX + token.lowercase()) &&
    ClipboardFingerprint.from(this) == fingerprint

/** Value-only clipboard identity used to avoid overwriting a newer rich clip. */
internal data class ClipboardFingerprint(
    val label: String?,
    val mimeTypes: List<String>,
    val descriptionExtras: List<Pair<String, String>>,
    val items: List<Item>,
) {
    data class Item(
        val text: String?,
        val htmlText: String?,
        val uri: String?,
        val intentUri: String?,
    )

    companion object {
        fun from(clip: ClipData?): ClipboardFingerprint? {
            clip ?: return null
            val description = clip.description
            return ClipboardFingerprint(
                label = description.label?.toString(),
                mimeTypes = (0 until description.mimeTypeCount).map(description::getMimeType),
                descriptionExtras = canonicalExtras(description.extras),
                items = (0 until clip.itemCount).map { index ->
                    val item = clip.getItemAt(index)
                    Item(
                        text = item.text?.toString(),
                        htmlText = item.htmlText,
                        uri = item.uri?.toString(),
                        intentUri = item.intent?.toUri(0),
                    )
                },
            )
        }

        private fun canonicalExtras(extras: PersistableBundle?): List<Pair<String, String>> {
            extras ?: return emptyList()
            return extras.keySet().sorted().map { key -> key to canonicalValue(extras[key]) }
        }

        private fun frame(value: String): String = "${value.length}:$value"

        private fun canonicalValue(value: Any?): String = when (value) {
            null -> "null"
            is BooleanArray -> "boolean:${value.size}:" +
                value.joinToString("") { frame(it.toString()) }
            is DoubleArray -> "double:${value.size}:" +
                value.joinToString("") { frame(it.toString()) }
            is IntArray -> "int:${value.size}:" +
                value.joinToString("") { frame(it.toString()) }
            is LongArray -> "long:${value.size}:" +
                value.joinToString("") { frame(it.toString()) }
            is Array<*> -> "array:${value.size}:" +
                value.joinToString("") { frame(canonicalValue(it)) }
            is PersistableBundle -> {
                val entries = canonicalExtras(value)
                "bundle:${entries.size}:" + entries.joinToString("") { (key, item) ->
                    frame(key) + frame(item)
                }
            }
            else -> "${value::class.java.name}:${frame(value.toString())}"
        }
    }
}
