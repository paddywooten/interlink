package com.interlink.app.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.interlink.app.core.Prefs
import com.interlink.app.service.LinkService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Restarts the link service after reboot (auto-reconnect on boot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Only start when paired — LinkClient would idle-loop otherwise.
        val cfg = runBlocking {
            runCatching { withTimeoutOrNull(3_000) { Prefs(context).snapshot() } }.getOrNull()
        }
        if (cfg?.secretB64 != null) {
            LinkService.start(context)
        }
    }
}
