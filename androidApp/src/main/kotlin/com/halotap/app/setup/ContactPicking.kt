package com.halotap.app.setup

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import com.halotap.app.escalation.EmergencyContact

/**
 * The intent that opens the system contact picker, restricted to phone-number rows.
 *
 * Deliberately a picker rather than READ_CONTACTS. The picker returns a one-shot grant for the
 * single row the user chose, so the app never holds a permission over the whole address book —
 * a safety app asking to read every contact is exactly the kind of request that gets it
 * uninstalled, and it is not needed.
 */
fun pickContactIntent(): Intent =
    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

/**
 * Reads the row the picker returned.
 *
 * Kept out of an ActivityResultContract on purpose: a contract's parse step is handed only an
 * Intent, and reading the row needs a ContentResolver.
 */
fun Context.resolvePickedContact(data: Intent?): EmergencyContact? {
    val uri = data?.data ?: return null
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
        val rawNumber = cursor.getString(1) ?: return@use null
        // Address books hold spaces, dashes and parentheses; SMS and tel: want neither.
        val number = rawNumber.filter { it.isDigit() || it == '+' }
        if (number.isBlank()) null else EmergencyContact(name = name, phoneNumber = number)
    }
}
