package com.interlink.app.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
 * Scope: every app except this one (see [mirrored]). Replies (RemoteInput)
 * arrive as notif.action {key, token, reply} requests from the laptop.
 */
class LinkNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val HISTORY_MAX = 100

        @Volatile
        var instance: LinkNotificationListener? = null
            private set

        /**
         * Whether the system has actually bound us.
         *
         * Not the same as being enabled in settings: a listener can be ticked
         * on and still be unbound (after an update, or while the app sits in
         * the force-stopped state), and when that happens *nothing* mirrors —
         * silently. The UI reports this so the gap is visible instead of
         * looking like a granted permission that does nothing.
         */
        val isConnected: Boolean
            get() = instance != null

        /**
         * Ask the system to re-bind us if we are enabled but not connected.
         *
         * Being ticked on in settings does not guarantee a binding: the system
         * leaves a listener unbound after an app update, and while it is
         * unbound *nothing* mirrors — no error, no notification, just silence.
         * `requestRebind` (API 26) is the platform's own remedy, so startup
         * tries to heal this without bothering the user.
         *
         * Best-effort by design: some builds (MIUI among them) ignore it, which
         * is why the UI also reports the unbound state and points at the
         * toggle. Returns immediately when already connected, and is ignored
         * when mirroring was never enabled.
         */
        fun requestRebindIfStale(context: Context) {
            if (instance != null) return
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            )?.contains(context.packageName) == true
            if (!enabled) return
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, LinkNotificationListener::class.java),
                )
                Log.i(TAG, "requested listener rebind")
            }.onFailure {
                Log.w(TAG, "rebind request failed: ${it.message}")
            }
        }

        /** token → action index within the notification's actions array. */
        /**
         * Never mirrored — our own app above all, whose foreground-service
         * "Connected" notification would otherwise echo straight back to the
         * laptop forever.
         */
        private val DEFAULT_DENY = setOf("com.interlink.app")

        /**
         * Whether this package's notifications are mirrored.
         *
         * Deliberately unconditional apart from [DEFAULT_DENY]. There used to
         * be an opt-in allowlist here, seeded by an early build with a short
         * list of SMS and dialer apps and stored in SharedPreferences. Nothing
         * has written it since, so on real devices it survived as a stale
         * five-package list that silently swallowed WhatsApp, the gallery and
         * every other notification while the listener stayed bound and healthy
         * — and because `com.android.shell` happened to be on it, an adb test
         * notification mirrored cleanly, making the whole feature look proven
         * end to end.
         *
         * A filter with no UI to change it and a failure mode of total silence
         * is a trap, so the only filter left is "not us". Kept in the
         * companion so it is testable without a live service.
         */
        internal fun mirrored(pkg: String?): Boolean = pkg != null && pkg !in DEFAULT_DENY

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
        // Logged before any filtering. This line is the difference between
        // "the system never told us" and "we chose not to mirror it" — a
        // distinction invisible from the laptop, and the whole ballgame when
        // a listener is bound yet mirrors nothing.
        Log.i(TAG, "posted ${sbn.packageName} key=${sbn.key}")
        if (!mirrored(sbn.packageName)) {
            Log.i(TAG, "  skip ${sbn.packageName}: not mirrored")
            return
        }
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
                        add(
                            objOf(
                                "label" to (a.title?.toString() ?: ""),
                                "token" to i.toString(),
                                // Which action carries the RemoteInput. The
                                // laptop needs this: it renders a generic
                                // Reply box but cannot tell which index to
                                // send the text back to.
                                "reply" to (a.remoteInputs?.isNotEmpty() == true),
                            ),
                        )
                    }
                },
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Symmetric with onNotificationPosted: proof the system reached us.
        Log.i(TAG, "removed ${sbn.packageName} key=${sbn.key}")
        if (!mirrored(sbn.packageName)) return
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

    /**
     * Laptop request: invoke action [token], optionally carrying [reply] text
     * for an action that takes RemoteInput (WhatsApp, SMS, …).
     *
     * Returns an error message, or null on success. The laptop toasts from
     * this, so a dropped action has to say so — reporting success for
     * something that never happened is worse than failing, because the user
     * has no reason to look again.
     *
     * [token] is an action index, but a reply may legitimately arrive blank:
     * the laptop renders a generic reply box and doesn't have to know the
     * index, so a blank token falls back to the first action that accepts
     * RemoteInput.
     */
    fun handleAction(key: String, token: String, reply: String?): String? {
        val target = activeNotifications?.firstOrNull { it.key == key }
            ?: return "that notification is no longer on the phone"
        val actions = target.notification.actions.orEmpty()
        if (actions.isEmpty()) return "that notification has no actions"

        // WhatsApp, and most messaging apps, put Reply first and something
        // harmless second — but the index is app-defined and may shift between
        // updates of the same notification, so it is resolved rather than
        // assumed (see resolveActionTarget).
        val acceptsReply = actions.map { !it.remoteInputs.isNullOrEmpty() }
        val idx = resolveActionTarget(token, reply, acceptsReply)
            ?: return if (reply != null) {
                "that notification offers no reply"
            } else {
                "unknown action '$token'"
            }
        val action = actions[idx]

        try {
            val intent = Intent()
            val inputs = action.remoteInputs.orEmpty()
            if (reply != null) {
                if (inputs.isEmpty()) return "that notification offers no reply"
                val bundle = android.os.Bundle()
                bundle.putCharSequence(inputs[0].resultKey, reply)
                RemoteInput.addResultsToIntent(inputs, intent, bundle)
            }
            val pending = action.actionIntent ?: return "that action can't be invoked"
            pending.send(this, 0, intent)
        } catch (e: Exception) {
            Log.w(TAG, "action '$idx' on $key failed: ${e.message}")
            return "the phone refused that action: ${e.message}"
        }
        return null
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
