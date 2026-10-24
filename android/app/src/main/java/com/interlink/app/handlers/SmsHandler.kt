package com.interlink.app.handlers

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.interlink.app.core.Envelope
import com.interlink.app.core.int
import com.interlink.app.core.long
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SMS: threads, messages, send, mark-read. Mirrors PROTOCOL.md §4.3. */
object SmsHandler {

    /** name lookup cache: normalized number → display name (from ContactsHandler).
     *
     * Reads `content://sms` directly and groups by `thread_id` rather than the
     * `Threads` table: several OEM builds (e.g. Xiaomi/HyperOS) expose a
     * `Threads` projection without `recipient_ids`, so querying it fails. The
     * per-message rows always carry `address`, which is what we actually need.
     */
    fun threads(context: Context): JsonObject {
        val names = ContactsHandler.nameMap(context)
        val unread = unreadCounts(context)

        // threadId → newest message in that thread (scan is date DESC).
        val latest = mutableMapOf<Long, Triple<String, String, Long>>()
        val order = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("thread_id", "address", "body", "date", "read"),
                null, null, "date DESC",
            )?.use { c ->
                val thIdx = c.getColumnIndexOrThrow("thread_id")
                val adIdx = c.getColumnIndexOrThrow("address")
                val bdIdx = c.getColumnIndexOrThrow("body")
                val dtIdx = c.getColumnIndexOrThrow("date")
                while (c.moveToNext()) {
                    val tid = c.getLong(thIdx)
                    if (latest.containsKey(tid)) continue // already captured newest for this thread
                    val address = c.getString(adIdx) ?: ""
                    val body = c.getString(bdIdx) ?: ""
                    val date = c.getLong(dtIdx)
                    latest[tid] = Triple(address, body, date)
                    order.add(tid)
                }
            }
        } catch (_: Exception) {
        }

        val items = buildJsonArray {
            for (tid in order) {
                val (address, body, date) = latest[tid] ?: continue
                add(
                    buildJsonObject {
                        put("id", tid.toString())
                        put("address", address)
                        names[ContactsHandler.normalize(address)]?.let { put("name", it) }
                        put("snippet", body)
                        put("unread", unread[tid] ?: 0)
                        put("date", date)
                    },
                )
            }
        }
        return buildJsonObject { put("threads", items) }
    }

    private fun unreadCounts(context: Context): Map<Long, Int> {
        val map = mutableMapOf<Long, Int>()
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("thread_id"), "read = 0", null, null,
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow("thread_id")
                while (c.moveToNext()) {
                    val t = c.getLong(idx)
                    map[t] = (map[t] ?: 0) + 1
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    fun messages(context: Context, threadId: String, limit: Int): JsonArray {
        val names = ContactsHandler.nameMap(context)
        val out = buildJsonArray {
            try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf("_id", "thread_id", "address", "body", "date", "read", "type"),
                    "thread_id = ?", arrayOf(threadId), "date DESC",
                )?.use { c ->
                    var n = 0
                    val idIdx = c.getColumnIndexOrThrow("_id")
                    val thIdx = c.getColumnIndexOrThrow("thread_id")
                    val adIdx = c.getColumnIndexOrThrow("address")
                    val bdIdx = c.getColumnIndexOrThrow("body")
                    val dtIdx = c.getColumnIndexOrThrow("date")
                    val rdIdx = c.getColumnIndexOrThrow("read")
                    val tyIdx = c.getColumnIndexOrThrow("type")
                    while (c.moveToNext() && n < limit) {
                        val type = c.getInt(tyIdx)
                        val address = c.getString(adIdx) ?: ""
                        add(
                            buildJsonObject {
                                put("id", c.getLong(idIdx).toString())
                                put("threadId", c.getLong(thIdx).toString())
                                put(
                                    "box",
                                    when (type) {
                                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                                        Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                                        Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                                        else -> "inbox"
                                    },
                                )
                                put("address", address)
                                names[ContactsHandler.normalize(address)]?.let { put("name", it) }
                                put("body", c.getString(bdIdx) ?: "")
                                put("date", c.getLong(dtIdx))
                                put("read", c.getInt(rdIdx) == 1)
                            },
                        )
                        n++
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    /** Resolve a thread id to its first address.
     *
     * Reads the newest message for the thread (carries `address` directly) so
     * this works on OEMs whose `Threads` table lacks `recipient_ids`; the old
     * `recipient_ids` → canonical_addresses lookup is kept only as a fallback.
     */
    fun addressForThread(context: Context, threadId: String): String? {
        // Preferred: the newest message row for this thread.
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("address"), "thread_id = ?", arrayOf(threadId), "date DESC",
            )?.use { c ->
                if (c.moveToFirst()) {
                    val a = c.getString(c.getColumnIndexOrThrow("address"))
                    if (!a.isNullOrBlank()) return a
                }
            }
        } catch (_: Exception) {
        }
        // Fallback: recipient_ids → canonical_addresses (may be missing on some OEMs).
        return try {
            val recIds = context.contentResolver.query(
                Telephony.Threads.CONTENT_URI,
                arrayOf("recipient_ids"), "_id = ?", arrayOf(threadId), null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("recipient_ids")) else null
            } ?: return null
            val first = recIds.split(" ").firstOrNull()?.toLongOrNull() ?: return null
            context.contentResolver.query(
                Uri.parse("content://sms/canonical_addresses"),
                arrayOf("address"), "_id = ?", arrayOf(first.toString()), null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    fun send(context: Context, d: JsonElement): JsonElement {
        val body = d.str("body") ?: throw Exception("internal: missing body")
        val address = d.str("address") ?: d.str("threadId")?.let { addressForThread(context, it) }
            ?: throw Exception("internal: missing recipient")
        if (address.isBlank()) throw Exception("internal: missing recipient")
        val sms = android.telephony.SmsManager.getDefault()
        sms.sendTextMessage(address, null, body, null, null)
        return objOf("id" to "local:${System.currentTimeMillis()}", "date" to System.currentTimeMillis())
    }

    fun markRead(context: Context, threadId: String) {
        try {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                android.content.ContentValues().apply { put("read", 1) },
                "thread_id = ? AND read = 0",
                arrayOf(threadId),
            )
        } catch (_: SecurityException) {
            // Non-default SMS app cannot write — thread stays visually unread on phone.
        }
    }

    fun payloadThreads(p: JsonElement): Boolean = p.str("threadId") != null || p.long("threadId") != null
    fun parseLimit(d: JsonElement): Int = (d.int("limit") ?: 200).coerceIn(1, 1000)
}

/** Envelope helpers shared by handlers. */
object Resp {
    fun ok(req: Envelope, payload: JsonElement): Envelope = Envelope.response(req.id, req.t, payload)
    fun err(req: Envelope, code: String): Envelope = Envelope.errorResponse(req.id, req.t, code)
}
