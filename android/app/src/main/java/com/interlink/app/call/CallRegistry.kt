package com.interlink.app.call

import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.util.Log
import com.interlink.app.core.objOf
import kotlinx.serialization.json.JsonElement

/**
 * Tracks the single call InCallService sees. Handlers (CallHandler) and the
 * service share state through this object; ids are stable across both.
 */
object CallRegistry {
    private const val TAG = "CallRegistry"

    @Volatile
    var active: Call? = null

    /**
     * Id pre-seeded by CallHandler.dial before the call materializes in
     * Telecom, so onCallAdded can adopt it for the outgoing call.
     */
    @Volatile
    var incomingCallId: String? = null

    /** Id currently attached to [active] (inbound or outbound). */
    @Volatile
    var currentCallId: String? = null

    /** "out" when we placed the tracked call, else "in". */
    @Volatile
    var direction: String = "in"

    @Volatile
    var activeSince: Long = 0L

    fun newCallId(): String = "c" + System.currentTimeMillis().toString(36)

    /** Adopt-or-assign an id for a freshly added call. */
    fun attach(call: Call): String {
        active = call
        val preseeded = incomingCallId
        val id = if (preseeded != null) {
            preseeded
        } else {
            direction = "in"
            newCallId()
        }
        incomingCallId = null
        currentCallId = id
        return id
    }

    fun detach(call: Call): String? {
        if (active === call) {
            active = null
            activeSince = 0L
        }
        val id = currentCallId
        currentCallId = null
        return id
    }

    /** Number from the call details handle (tel: URI scheme-specific part). */
    fun number(call: Call): String = runCatching {
        val handle: Uri? = call.details?.handle
        if (handle != null && handle.scheme == "tel") handle.schemeSpecificPart else null
    }.getOrNull() ?: ""

    /** Contact display name for a number (best effort). */
    fun nameFor(context: android.content.Context, number: String): String? {
        if (number.isBlank()) return null
        return runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number),
                ),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** PROTOCOL.md §4.6 call.state payload builder. */
    fun statePayload(
        state: String,
        callId: String,
        number: String,
        name: String?,
        durationSec: Long? = null,
    ): JsonElement = objOf(
        "state" to state,
        "dir" to direction,
        "number" to number,
        "name" to (name ?: ""),
        "callId" to callId,
        "duration" to durationSec,
    )

    fun log(msg: String) = Log.d(TAG, msg)
}
