package com.interlink.app.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.interlink.app.core.Bus
import com.interlink.app.core.objOf

/**
 * Pushes received SMS to the laptop (PROTOCOL.md §4.3: sms.received carries
 * the message; sms.changed tells the laptop to refetch the thread list — the
 * only one of the two server.rs currently forwards, so both are sent).
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        for (msg in messages) {
            val address = msg.displayOriginatingAddress ?: ""
            val body = msg.messageBody ?: ""
            val time = msg.timestampMillis
            Log.i("SmsReceiver", "sms from $address (${body.length} B)")
            Bus.push("sms.received", objOf("address" to address, "body" to body, "time" to time))
        }
        Bus.push("sms.changed", objOf("kind" to "new"))
    }
}
