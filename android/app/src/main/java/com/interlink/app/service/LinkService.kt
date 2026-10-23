package com.interlink.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.interlink.app.App
import com.interlink.app.MainActivity
import com.interlink.app.R
import com.interlink.app.core.Bus
import com.interlink.app.core.Envelope
import com.interlink.app.core.LinkClient
import com.interlink.app.core.LinkSession
import com.interlink.app.core.LinkState
import com.interlink.app.core.Prefs
import com.interlink.app.core.int
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import com.interlink.app.handlers.CallHandler
import com.interlink.app.handlers.ContactsHandler
import com.interlink.app.handlers.DeviceHandler
import com.interlink.app.handlers.MediaHandler
import com.interlink.app.handlers.SmsHandler
import com.interlink.app.notif.LinkNotificationListener
import com.interlink.app.notif.notifHistoryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Foreground service hosting the WebSocket link. Routes laptop requests
 * (Envelope.t) to the per-domain handlers per PROTOCOL.md §4 and what
 * desktop/src-tauri/src/server.rs / commands.rs actually send.
 *
 * Handlers throw Exception with a stable code prefix ("perm:", "busy:",
 * "not_found:", "internal:") which is passed through as the `e` field of an
 * ok=false response (PROTOCOL.md §4.8). Handlers that stream DATA frames
 * (media.thumb, media.get) respond on the session themselves.
 */
class LinkService : Service() {

    companion object {
        private const val TAG = "LinkService"
        const val NOTIF_ID = 10

        val state = MutableStateFlow(LinkState.DISCONNECTED)
        val stateDetail = MutableStateFlow<String?>(null)

        @Volatile
        var instance: LinkService? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var client: LinkClient
    private lateinit var callHandler: CallHandler

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        client = LinkClient(scope, prefs, ::dispatch, ::onState)
        callHandler = CallHandler(this, client)
        Bus.client = client
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Connecting…")
        // Idempotent — survives repeated start commands and process restarts.
        client.start()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        Bus.client = null
        client.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ——— foreground notification ———

    private fun startAsForeground(text: String) {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun onState(s: LinkState, detail: String?) {
        state.value = s
        stateDetail.value = detail
        val text = when (s) {
            LinkState.CONNECTED -> "Connected to ${detail ?: "laptop"}"
            LinkState.CONNECTING, LinkState.HANDSHAKING -> "Connecting…"
            LinkState.DISCONNECTED -> if (detail == "unpaired") "Not paired" else "Disconnected"
        }
        runCatching {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        }
    }

    // ——— request routing ———

    private suspend fun dispatch(env: Envelope, session: LinkSession) {
        try {
            when (env.t) {
                "sms.threads" -> respond(session, env, SmsHandler.threads(this))

                "sms.list" -> {
                    val threadId = env.d.str("threadId")
                        ?: throw Exception("internal: missing threadId")
                    val limit = (env.d.int("limit") ?: 200).coerceIn(1, 1000)
                    respond(session, env, objOf("messages" to SmsHandler.messages(this, threadId, limit)))
                }

                "sms.send" -> {
                    val result = SmsHandler.send(this, env.d)
                    Bus.push("sms.changed", objOf("kind" to "sent"))
                    respond(session, env, result)
                }

                "sms.read" -> {
                    val threadId = env.d.str("threadId")
                        ?: throw Exception("internal: missing threadId")
                    SmsHandler.markRead(this, threadId)
                    respond(session, env, objOf())
                }

                "contacts.query" -> {
                    val q = env.d.str("q")
                    val limit = (env.d.int("limit") ?: 50).coerceIn(1, 50)
                    respond(session, env, ContactsHandler.search(this, q, limit))
                }

                "media.list" -> respond(session, env, MediaHandler.list(this, env.d))

                // thumb/get respond on the session themselves, then stream.
                "media.thumb" -> MediaHandler.thumb(this, session, env)
                "media.get" -> MediaHandler.get(this, session, env)

                "file.offer" -> respond(session, env, MediaHandler.receiveOffer(this, client, env.d))

                "call.dial" -> respond(session, env, callHandler.dial(env.d))
                "call.answer" -> respond(session, env, callHandler.answer(env.d))
                "call.decline" -> respond(session, env, callHandler.decline(env.d))
                "call.end" -> respond(session, env, callHandler.end(env.d))
                "call.audio_start" -> respond(session, env, callHandler.audioStart(env.d))
                "call.audio_stop" -> respond(session, env, callHandler.audioStop(env.d))

                "clip.get" -> respond(session, env, DeviceHandler.clipGet(this))
                "clip.set" -> respond(session, env, DeviceHandler.clipSet(this, env.d))

                "device.info" -> {
                    val name = prefs.snapshot().deviceName
                    respond(session, env, DeviceHandler.info(this, name))
                }

                "device.battery" -> respond(session, env, DeviceHandler.battery(this))

                "device.ring", "find.phone" -> {
                    DeviceHandler.ring(this, env.d.int("seconds") ?: 10)
                    respond(session, env, objOf())
                }

                "notif.action" -> {
                    val key = env.d.str("key") ?: throw Exception("internal: missing key")
                    val token = env.d.str("token") ?: throw Exception("internal: missing token")
                    LinkNotificationListener.instance?.handleAction(key, token, env.d.str("reply"))
                    respond(session, env, objOf())
                }

                "notif.dismiss" -> {
                    val key = env.d.str("key") ?: throw Exception("internal: missing key")
                    LinkNotificationListener.instance?.dismiss(key)
                    respond(session, env, objOf())
                }

                "notif.history" -> respond(session, env, notifHistoryResponse(env.d.int("limit") ?: 50))

                "unpair" -> {
                    prefs.clearPairing()
                    respond(session, env, objOf())
                }

                else -> {
                    // Unknown PUSH types must be ignored (§6); replying empty to
                    // an unknown request at least resolves its pending waiter.
                    Log.i(TAG, "ignoring unknown type ${env.t}")
                    respond(session, env, objOf())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            fail(session, env, "perm: ${e.message ?: "permission not granted"}")
        } catch (e: Exception) {
            fail(session, env, e.message.toCode())
        }
    }

    private suspend fun respond(session: LinkSession, env: Envelope, payload: JsonElement) {
        session.respond(Envelope.response(env.id, env.t, payload))
    }

    private suspend fun fail(session: LinkSession, env: Envelope, code: String) {
        runCatching { session.respond(Envelope.errorResponse(env.id, env.t, code)) }
    }

    private fun String?.toCode(): String {
        val msg = this ?: "unknown error"
        return if (msg.startsWith("perm:") || msg.startsWith("busy:") ||
            msg.startsWith("not_found:") || msg.startsWith("internal:")
        ) msg else "internal: $msg"
    }
}
