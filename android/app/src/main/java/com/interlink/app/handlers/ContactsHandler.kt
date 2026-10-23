package com.interlink.app.handlers

import android.content.Context
import android.provider.ContactsContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Contacts search for the laptop (dialer / new-message pickers). */
object ContactsHandler {

    /** E.164-ish normalization for matching: strip everything but digits and leading +. */
    fun normalize(num: String): String = num.filter { it.isDigit() || it == '+' }

    private var cachedNames: Map<String, String> = emptyMap()
    private var cachedAt = 0L

    /** number → display name (cached for 60 s). */
    fun nameMap(context: Context): Map<String, String> {
        if (System.currentTimeMillis() - cachedAt < 60_000 && cachedNames.isNotEmpty()) return cachedNames
        val map = mutableMapOf<String, String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { c ->
                val nIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val dIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val num = c.getString(nIdx) ?: continue
                    val name = c.getString(dIdx) ?: continue
                    map[normalize(num)] = name
                }
            }
        } catch (_: Exception) {
        }
        cachedNames = map
        cachedAt = System.currentTimeMillis()
        return map
    }

    fun search(context: Context, q: String?, limit: Int): JsonObject {
        val sel: String?
        val args: Array<String>?
        if (q.isNullOrBlank()) {
            sel = null
            args = null
        } else {
            sel = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            args = arrayOf("%$q%", "%$q%")
        }
        // contactId → (name, phones)
        val byContact = LinkedHashMap<Long, Pair<MutableSet<String>, MutableList<String>>>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                sel, args,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                var n = 0
                val cIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val dIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (c.moveToNext() && n < limit * 3) {
                    val cid = c.getLong(cIdx)
                    val num = c.getString(nIdx) ?: continue
                    val name = c.getString(dIdx) ?: continue
                    val entry = byContact.getOrPut(cid) { mutableSetOf<String>() to mutableListOf() }
                    if (!entry.first.contains(name)) entry.first.add(name)
                    if (!entry.second.contains(num)) entry.second.add(num)
                    n++
                }
            }
        } catch (_: Exception) {
        }
        val items: JsonArray = buildJsonArray {
            var done = 0
            for ((cid, pair) in byContact) {
                if (done >= limit) break
                add(
                    buildJsonObject {
                        put("id", cid.toString())
                        put("name", pair.first.firstOrNull() ?: "")
                        put("phones", kotlinx.serialization.json.JsonArray(pair.second.map {
                            kotlinx.serialization.json.JsonPrimitive(it)
                        }))
                    },
                )
                done++
            }
        }
        return buildJsonObject { put("contacts", items) }
    }
}
