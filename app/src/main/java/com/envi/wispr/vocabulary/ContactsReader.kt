package com.envi.wispr.vocabulary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Outcome of reading structured contact names from Android's ContactsProvider (#32). */
internal sealed interface ContactsReadResult {
    data object PermissionDenied : ContactsReadResult
    data class Candidates(val names: List<ContactCandidateName>) : ContactsReadResult
}

/**
 * Reads structured contact given/family names directly on the phone (#32).
 * Never queries phone numbers, emails, addresses, notes, or photos.
 */
internal object ContactsReader {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun readCandidates(context: Context): ContactsReadResult {
        if (!hasPermission(context)) return ContactsReadResult.PermissionDenied
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
        )
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        val out = mutableListOf<ContactCandidateName>()
        return try {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val givenIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                val familyIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                val displayIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                    var given = if (givenIdx >= 0) cursor.getString(givenIdx) else null
                    var family = if (familyIdx >= 0) cursor.getString(familyIdx) else null
                    if (given.isNullOrBlank() && family.isNullOrBlank() && displayIdx >= 0) {
                        val display = cursor.getString(displayIdx)?.trim().orEmpty()
                        if (!display.contains('@')) {
                            val parts = display.split(Regex("\\s+"))
                            given = parts.getOrNull(0)
                            family = if (parts.size > 1) parts.last() else null
                        }
                    }
                    if (!given.isNullOrBlank() || !family.isNullOrBlank()) {
                        out += ContactCandidateName(contactId = id, given = given, family = family)
                    }
                }
            }
            ContactsReadResult.Candidates(out)
        } catch (_: SecurityException) {
            ContactsReadResult.PermissionDenied
        }
    }
}
