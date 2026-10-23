package com.interlink.app.core

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var client: LinkClient? = null

    /** Fire-and-forget protocol push; silently drops when offline. */
    fun push(type: String, payload: JsonElement) {
        val c = client ?: return
        scope.launch {
            runCatching { c.push(type, payload) }
        }
    }
}
