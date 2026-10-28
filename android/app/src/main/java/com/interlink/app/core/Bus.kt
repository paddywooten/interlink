package com.interlink.app.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Static bridge so out-of-process callbacks (BroadcastReceivers,
 * NotificationListenerService, InCallService) can reach the running
 * [LinkClient] owned by [com.interlink.app.service.LinkService].
 */
object Bus {
    private const val TAG = "Bus"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var client: LinkClient? = null

    /**
     * Fire-and-forget protocol push; drops when offline.
     *
     * Drops and failures are logged rather than swallowed. This is the last
     * hop before the laptop, so a silent drop here is indistinguishable from
     * the phone never being notified in the first place — exactly the
     * ambiguity that made "notifications don't mirror" undiagnosable.
     */
    fun push(type: String, payload: JsonElement) {
        val c = client
        if (c == null) {
            Log.w(TAG, "drop $type: no link client")
            return
        }
        scope.launch {
            runCatching { c.push(type, payload) }
                .onFailure {
                    Log.w(TAG, "drop $type: ${it.javaClass.simpleName}: ${it.message}")
                }
        }
    }
}
