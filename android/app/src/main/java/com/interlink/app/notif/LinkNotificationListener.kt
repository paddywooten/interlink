package com.interlink.app.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.interlink.app.core.Bus
import com.interlink.app.core.objOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque

/**
 * Mirrors notifications to the laptop (PROTOCOL.md §4.2).
 *
 * Allowlist: default = SMS/MMS/dialer apps, overridable via the
 * "il_notif" SharedPreferences set "allow". Replies (RemoteInput) arrive as
 * notif.action {key, token, reply} requests from the laptop.
 */
class LinkNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val HISTORY_MAX = 100

        @Volatile
        var instance: LinkNotificationListener? = null
            private set

        /** token → action index within the notification's actions array. */
        private val DEFAULT_ALLOW = setOf(
            "com.android.mms",
            "com.android.mms.transaction",
            "com.google.android.apps.messaging",
            "com.samsung.android.mms",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
        )

        /** Recent dismissals, newest first — served by notif.history. */
        private val history = ArrayDeque<JsonObject>()

        @Synchronized
        private fun remember(item: JsonObject) {
            history.addFirst(item)
            while (history.size > HISTORY_MAX) history.removeLast()
        }

        fun historyItems(limit: Int): JsonArray {
            val out = buildJsonArray {
                val n = limit.coerceIn(1, 100)
                var i = 0
                synchronized(this@Companion) {
                    for (item in history) {
                        if (i >= n) break
                        add(item)
                        i++
                    }
                }
            }
            return out
        }
    }

    private val allow: Set<String>
        get() = getSharedPreferences("il_notif", MODE_PRIVATE)
            .getStringSet("allow", DEFAULT_ALLOW) ?: DEFAULT_ALLOW

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != null && sbn.packageName !in allow) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val appName = appName(sbn.packageName)
        val hasReply = n.actions?.any { a ->
            a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null
        } == true

        Bus.push(
            "notif.post",
            objOf(
                "key" to sbn.key,
                "pkg" to (sbn.packageName ?: ""),
                "app" to appName,
                "title" to title,
                "text" to text,
                "bigText" to bigText,
                "subText" to subText,
                "category" to n.category,
                "when" to n.`when`,
                "ongoing" to sbn.isOngoing,
                "hasReply" to hasReply,
                "actions" to buildJsonArray {
                    n.actions?.forEachIndexed { i, a ->
                        add(objOf("label" to (a.title?.toString() ?: ""), "token" to i.toString()))
                    }
                },
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != null && sbn.packageName !in allow) return
        val n = sbn.notification
        val extras = n?.extras
        remember(
            objOf(
                "key" to sbn.key,
                "pkg" to (sbn.packageName ?: ""),
                "app" to appName(sbn.packageName),
                "title" to (extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""),
                "text" to (extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""),
                "when" to (n?.`when` ?: 0L),
                "ongoing" to sbn.isOngoing,
            ),
        )
        Bus.push("notif.remove", objOf("key" to sbn.key, "pkg" to (sbn.packageName ?: "")))
    }

    /** Laptop request: invoke action [token] (index), optionally with reply text. */
    fun handleAction(key: String, token: String, reply: String?) {
        val idx = token.toIntOrNull() ?: return
        val target = activeNotifications?.firstOrNull { it.key == key } ?: return
        val action = target.notification.actions?.getOrNull(idx) ?: return
        try {
            val intent = Intent()
            val inputs = action.remoteInputs ?: emptyArray()
            if (reply != null && inputs.isNotEmpty()) {
                val bundle = android.os.Bundle()
                bundle.putCharSequence(inputs[0].resultKey, reply)
                RemoteInput.addResultsToIntent(inputs, intent, bundle)
            }
            action.actionIntent?.send(this, 0, intent)
        } catch (e: Exception) {
            Log.w(TAG, "action $idx on $key failed: ${e.message}")
        }
    }

    fun dismiss(key: String) {
        runCatching { cancelNotification(key) }
    }

    private fun appName(pkg: String?): String {
        if (pkg == null) return ""
        return runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }
}

/** Payload builder for notif.history responses. */
fun notifHistoryResponse(limit: Int): JsonElement = buildJsonObject {
    put("items", LinkNotificationListener.historyItems(limit))
}
