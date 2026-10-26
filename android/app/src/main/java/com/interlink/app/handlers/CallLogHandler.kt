package com.interlink.app.handlers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's call history for the laptop's Calls page (PROTOCOL.md §4.6).
 *
 * `READ_CALL_LOG` is its own runtime permission: `CALL_PHONE` lets you *place*
 * a call and `ANSWER_PHONE_CALLS` lets you *take* one, and neither of them
 * says anything about being allowed to read who called whom. So it is checked
 * here and reported with the same stable `perm:` prefix the other handlers
 * use — the laptop shows it as an instruction rather than an empty list, which
 * is the difference between "nobody called" and "you haven't allowed this".
 *
 * Names are resolved **on the phone**: [ContactsHandler.nameMap] is a 60 s
 * cache of the whole address book that already degrades to empty without
 * `READ_CONTACTS`, falling back to the dialer's own `CACHED_NAME`. Doing it
 * here keeps the wire free of a number→name join the laptop would otherwise
 * redo against its own copy of the book.
 */
object CallLogHandler {

    /** Android's CallLog type constants → the wire's closed vocabulary. */
    fun kindOf(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "in"
        CallLog.Calls.OUTGOING_TYPE -> "out"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "answered"
        else -> "unknown"
    }

    private fun str(c: Cursor, idx: Int): String? = if (idx < 0) null else c.getString(idx)

    private fun int(c: Cursor, idx: Int, fallback: Int = 0): Int =
        if (idx < 0) fallback else c.getInt(idx)

    /**
     * `{calls:[{id, number, name, kind, date, duration, new}]}`, newest first.
     *
     * `date` is epoch **milliseconds**, `duration` is whole **seconds** (both
     * as the provider stores them), `kind` is one of in / out / missed /
     * voicemail / rejected / blocked / answered / unknown.
     */
    fun list(context: Context, limit: Int): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw Exception("perm: call log permission not granted")
        }
        val names = ContactsHandler.nameMap(context)
        val rows = mutableListOf<JsonObject>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.NEW,
                    CallLog.Calls.CACHED_NAME,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC", // the log is read newest-first, always
            )?.use { c ->
                val idIdx = c.getColumnIndex(CallLog.Calls._ID)
                val numIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val newIdx = c.getColumnIndex(CallLog.Calls.NEW)
                val cachedIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                if (numIdx < 0 || dateIdx < 0) return@use // provider without a number/date
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val number = c.getString(numIdx) ?: ""
                    // Never send a bare private number without trying the book
                    // first; nameMap is already cached, so this is free.
                    val known = if (number.isEmpty()) {
                        null
                    } else {
                        names[ContactsHandler.normalize(number)]
                    }
                    val name = known ?: str(c, cachedIdx) ?: ""
                    // DURATION is a String column ("187"), not an int.
                    val duration = str(c, durIdx)?.toLongOrNull() ?: 0L
                    rows.add(
                        buildJsonObject {
                            put("id", (if (idIdx < 0) 0L else c.getLong(idIdx)).toString())
                            put("number", number)
                            put("name", name)
                            put("kind", kindOf(int(c, typeIdx)))
                            put("date", c.getLong(dateIdx))
                            put("duration", duration)
                            put("new", int(c, newIdx) == 1)
                        },
                    )
                    n++
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked mid-flight (or a device that treats the
            // column as app-op gated) — same story as never having it.
            throw Exception("perm: call log permission not granted")
        }
        return buildJsonObject { put("calls", JsonArray(rows)) }
    }
}
