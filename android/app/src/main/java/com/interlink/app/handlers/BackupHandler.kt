package com.interlink.app.handlers

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.interlink.app.core.obj
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App & data backup / restore (PROTOCOL.md §4.11).
 *
 * `backup.export` returns the payload as plain text in the response:
 *   - contacts as vCard 3.0 (`contacts.vcf`)
 *   - SMS as a JSON array (`sms.json`)
 * `backup.import` takes one of those files back and writes it into the phone.
 *
 * Photo backup/restore travels as ordinary files (media.get on the way out,
 * `file.offer` with `dest:"media"` on the way in) — no giant archives.
 *
 * Restoring SMS is only possible for the *default SMS app*: since KitKat
 * Android rejects writes to the telephony provider from anyone else, so that
 * section reports `blocked` instead of failing the whole restore.
 */
object BackupHandler {
    private const val TAG = "BackupHandler"
    /** Bound the SMS export so the response stays well under WS frame limits. */
    private const val SMS_LIMIT = 20_000

    private fun sections(d: JsonElement): List<String> {
        val raw = (d.obj("sections") as? JsonArray) ?: return listOf("contacts", "sms")
        val list = raw.mapNotNull { it.jsonPrimitive.contentOrNullSafe }
        return if (list.isEmpty()) listOf("contacts", "sms") else list
    }

    private val kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe: String?
        get() = if (isString) content else null

    // ——— export ———

    fun export(context: Context, d: JsonElement): JsonObject {
        val want = sections(d)
        return buildJsonObject {
            if (want.contains("contacts")) put("contacts", exportVcf(context))
            if (want.contains("sms")) put("sms", exportSms(context))
            put("at", System.currentTimeMillis())
        }
    }

    /** ContactsContract → vCard 3.0 (name + phone numbers). */
    fun exportVcf(context: Context): String {
        val byContact = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { c ->
                val cIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val dIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val cid = c.getLong(cIdx)
                    val num = c.getString(nIdx) ?: continue
                    val name = c.getString(dIdx) ?: ""
                    val entry = byContact.getOrPut(cid) { name to mutableListOf() }
                    if (!entry.second.contains(num)) entry.second.add(num)
                }
            }
        } catch (e: SecurityException) {
            throw Exception("perm: contacts permission not granted")
        }
        val sb = StringBuilder()
        for ((_, pair) in byContact) {
            val name = pair.first
            val nums = pair.second
            if (name.isBlank() && nums.isEmpty()) continue
            sb.append("BEGIN:VCARD\nVERSION:3.0\n")
            if (name.isNotBlank()) sb.append("FN:").append(escapeVcf(name)).append('\n')
            for (n in nums) sb.append("TEL;TYPE=CELL:").append(escapeVcf(n)).append('\n')
            sb.append("END:VCARD\n")
        }
        return sb.toString()
    }

    private fun escapeVcf(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace(",", "\\,").replace(";", "\\;")

    /** Telephony.Sms → JSON array (most recent SMS_LIMIT messages). */
    fun exportSms(context: Context): String {
        val arr = kotlinx.serialization.json.buildJsonArray {
            try {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.READ,
                    ),
                    null, null,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { c ->
                    val aIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bIdx = c.getColumnIndex(Telephony.Sms.BODY)
                    val dIdx = c.getColumnIndex(Telephony.Sms.DATE)
                    val tIdx = c.getColumnIndex(Telephony.Sms.TYPE)
                    val rIdx = c.getColumnIndex(Telephony.Sms.READ)
                    var n = 0
                    while (c.moveToNext() && n < SMS_LIMIT) {
                        add(
                            buildJsonObject {
                                put("address", c.getString(aIdx) ?: "")
                                put("body", c.getString(bIdx) ?: "")
                                put("date", if (dIdx >= 0) c.getLong(dIdx) else 0L)
                                put("type", if (tIdx >= 0) c.getInt(tIdx) else 1)
                                put("read", if (rIdx >= 0 && c.getInt(rIdx) == 1) true else false)
                            },
                        )
                        n++
                    }
                }
            } catch (e: SecurityException) {
                throw Exception("perm: sms permission not granted")
            }
        }
        return arr.toString()
    }

    // ——— import ———

    fun import(context: Context, d: JsonElement): JsonObject {
        val kind = d.str("kind") ?: throw Exception("internal: missing kind")
        val data = d.str("data") ?: ""
        return when (kind) {
            "contacts" -> importVcf(context, data)
            "sms" -> importSms(context, data)
            else -> throw Exception("internal: unknown backup section $kind")
        }
    }

    /** vCard → ContactsContract raw contacts (needs WRITE_CONTACTS). */
    fun importVcf(context: Context, vcf: String): JsonObject {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw Exception("perm: contacts write permission not granted")
        }
        val resolver = context.contentResolver
        var imported = 0
        var skipped = 0
        // Restore must be idempotent: a backup taken from this phone and
        // restored onto the same phone would otherwise double every contact.
        // Numbers are the identity (a backup vCard carries the same digits it
        // was exported with, so comparing digits matches the round trip).
        val have = existingNumbers(resolver)

        var name = ""
        val numbers = mutableListOf<String>()
        var inCard = false
        for (raw in vcf.lineSequence()) {
            val line = raw.trimEnd('\r')
            when {
                line.equals("BEGIN:VCARD", ignoreCase = true) -> {
                    inCard = true; name = ""; numbers.clear()
                }
                line.equals("END:VCARD", ignoreCase = true) -> {
                    if (!inCard) continue
                    inCard = false
                    if (name.isBlank() && numbers.isEmpty()) { skipped++; continue }
                    if (numbers.all { normalizeNumber(it) in have }) { skipped++; continue }
                    try {
                        insertContact(resolver, name, numbers)
                        numbers.forEach { have.add(normalizeNumber(it)) }
                        imported++
                    } catch (e: Exception) {
                        skipped++
                        Log.w(TAG, "contact import failed: ${e.message}")
                    }
                }
                !inCard -> {}
                line.startsWith("FN:", ignoreCase = true) -> name = unescapeVcf(line.substring(3))
                line.startsWith("N:", ignoreCase = true) && name.isBlank() -> {
                    // "Last;First;;;" — take the given name, else the whole field.
                    val parts = unescapeVcf(line.substring(2)).split(';')
                    name = parts.getOrNull(1)?.ifBlank { parts.getOrNull(0).orEmpty() }.orEmpty()
                }
                line.startsWith("TEL", ignoreCase = true) -> {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val num = unescapeVcf(line.substring(idx + 1)).trim()
                        if (num.isNotBlank() && !numbers.contains(num)) numbers.add(num)
                    }
                }
            }
        }
        return objOf("imported" to imported, "skipped" to skipped)
    }

    /** Every phone number already in the address book, normalized. */
    private fun existingNumbers(resolver: android.content.ContentResolver): MutableSet<String> {
        val out = mutableSetOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val n = c.getString(idx) ?: continue
                if (n.isNotBlank()) out.add(normalizeNumber(n))
            }
        }
        return out
    }

    /**
     * Digits only, so `+233 24 123 4567` and `0241234567` written with different
     * spacing still compare equal. Deliberately *not* country-code aware — a
     * false "already have it" would silently drop a contact on restore, which
     * is far worse than an occasional duplicate.
     */
    private fun normalizeNumber(s: String) = s.filter { it.isDigit() }

    private fun unescapeVcf(s: String): String = s
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")

    private fun insertContact(resolver: android.content.ContentResolver, name: String, numbers: List<String>) {
        val raw = ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
        }
        val rawUri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, raw)
            ?: throw Exception("cannot create contact")
        val rawId = ContentUris.parseId(rawUri)

        if (name.isNotBlank()) {
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                },
            )
        }
        for (num in numbers) {
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, num)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                },
            )
        }
    }

    /**
     * JSON → Telephony.Sms. Only the default SMS app may write there, so this
     * reports `blocked` (instead of throwing) when we aren't it.
     */
    fun importSms(context: Context, json: String): JsonObject {
        val defaultSms = Telephony.Sms.getDefaultSmsPackage(context)
        if (defaultSms != context.packageName) {
            return objOf(
                "imported" to 0,
                "blocked" to true,
                "error" to "Android only allows the default SMS app to write messages. " +
                    "Export always works; re-import after setting InterLink as your SMS app.",
            )
        }
        val resolver = context.contentResolver
        var imported = 0
        var skipped = 0
        val arr = try {
            kotlinx.serialization.json.Json.parseToJsonElement(json).jsonArray
        } catch (e: Exception) {
            throw Exception("internal: malformed sms.json (${e.message})")
        }
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            try {
                val cv = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, o.str("address") ?: "")
                    put(Telephony.Sms.BODY, o.str("body") ?: "")
                    put(Telephony.Sms.DATE, o.longOf("date") ?: 0L)
                    put(Telephony.Sms.TYPE, o.intOf("type") ?: Telephony.Sms.MESSAGE_TYPE_INBOX)
                    put(Telephony.Sms.READ, if (o.boolOf("read") == true) 1 else 0)
                    put(Telephony.Sms.SEEN, 1)
                }
                resolver.insert(Telephony.Sms.CONTENT_URI, cv)
                imported++
            } catch (e: Exception) {
                skipped++
            }
        }
        return objOf("imported" to imported, "skipped" to skipped, "blocked" to false)
    }

    private fun JsonObject.longOf(k: String): Long? =
        (get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
    private fun JsonObject.intOf(k: String): Int? = longOf(k)?.toInt()
    private fun JsonObject.boolOf(k: String): Boolean? =
        (get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
}
