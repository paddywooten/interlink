package com.interlink.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import com.interlink.app.handlers.AppHandler
import com.interlink.app.handlers.BackupHandler
import com.interlink.app.handlers.CallHandler
import com.interlink.app.handlers.ContactsHandler
import com.interlink.app.handlers.DeviceHandler
import com.interlink.app.handlers.MediaHandler
import com.interlink.app.handlers.SmsHandler
import com.interlink.app.notif.LinkNotificationListener
import com.interlink.app.notif.notifHistoryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

        /**
         * Whether the OS may still throttle or kill us while the screen is off.
         *
         * A foreground service is not enough on its own: when the phone goes
         * into doze the system will happily suspend our process and drop the
         * socket, so the link dies the moment the screen locks. "Don't
         * optimise" (a.k.a. unrestricted battery) is what keeps it alive.
         */
        fun batteryOptimizationsIgnored(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: Prefs
    private lateinit var client: LinkClient
    private lateinit var callHandler: CallHandler
    private var batteryJob: Job? = null

    /** Held for the life of the service — see [acquireKeepalive]. */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        client = LinkClient(scope, prefs, ::dispatch, ::onState)
        callHandler = CallHandler(this, client)
        Bus.client = client
        acquireKeepalive()
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
        releaseKeepalive()
        client.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ——— keep-alive ———

    /**
     * Stop the OS suspending us while the phone sleeps.
     *
     * Without this the link dies as soon as the screen turns off: doze
     * suspends the CPU, the WebSocket goes quiet, and the laptop sees a dead
     * phone even though the service is nominally running. A partial wakelock
     * keeps the CPU able to service the socket, and a high-performance WiFi
     * lock stops the radio being parked into power-save between packets.
     *
     * These are cheap because the service is a persistent foreground service
     * doing nothing but holding a socket; the CPU stays idle between frames
     * either way, it just isn't *suspended*.
     */
    private fun acquireKeepalive() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InterLink:link").apply {
                // One reference for the whole service lifetime, so a repeated
                // start can't leave us holding it forever after a stop.
                setReferenceCounted(false)
                @Suppress("DEPRECATION") // LOW_LATENCY is the modern flag; FULL_HIGH_PERF predates 29
                acquire()
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "InterLink:wifi",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseKeepalive() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

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
        if (s == LinkState.CONNECTED) startBatteryTicker() else stopBatteryTicker()
    }

    /**
     * Battery percentage for the laptop's dashboard/sidebar: pushed once on
     * connect and then every 30 s so the value never sits stale at "—".
     */
    private fun startBatteryTicker() {
        if (batteryJob?.isActive == true) return
        batteryJob = scope.launch {
            while (isActive) {
                runCatching { Bus.push("device.battery", DeviceHandler.battery(this@LinkService)) }
                delay(30_000)
            }
        }
    }

    private fun stopBatteryTicker() {
        batteryJob?.cancel()
        batteryJob = null
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
                    // 5000 lets the laptop browse the whole address book; the
                    // dialer/new-message pickers still pass small limits.
                    val limit = (env.d.int("limit") ?: 50).coerceIn(1, 5000)
                    respond(session, env, ContactsHandler.search(this, q, limit))
                }

                "media.list" -> respond(session, env, MediaHandler.list(this, env.d))

                // thumb/get respond on the session themselves, then stream.
                "media.thumb" -> MediaHandler.thumb(this, session, env)
                "media.get" -> MediaHandler.get(this, session, env)

                "file.offer" -> respond(session, env, MediaHandler.receiveOffer(this, client, env.d))

                // Remote app install: the laptop already streamed the .apk in
                // via file.offer; we now hand it to the system installer
                // (PackageInstaller session → user confirmation on the phone).
                "app.install" -> respond(session, env, AppHandler.install(this, env.d))

                // Backup / restore (PROTOCOL.md §4.11). Photos travel as
                // ordinary files; these two carry contacts + SMS.
                "backup.export" -> respond(session, env, BackupHandler.export(this, env.d))
                "backup.import" -> respond(session, env, BackupHandler.import(this, env.d))

                "call.dial" -> respond(session, env, callHandler.dial(env.d))
                "call.answer" -> respond(session, env, callHandler.answer(env.d))
                "call.decline" -> respond(session, env, callHandler.decline(env.d))
                "call.end" -> respond(session, env, callHandler.end(env.d))
                "call.audio_start" -> respond(session, env, callHandler.audioStart(env.d))
                "call.audio_stop" -> respond(session, env, callHandler.audioStop(env.d))

                "clip.get" -> respond(session, env, DeviceHandler.clipGet(this))
                "clip.set" -> respond(session, env, DeviceHandler.clipSet(this, env.d))

                // Screen mirroring: launch the consent trampoline (which starts
                // the MediaProjection foreground service on approval) and reply
                // immediately; the real start/stop is reported via mirror.state.
                "mirror.start" -> {
                    val w = env.d.int("w") ?: 720
                    val f = env.d.int("fps") ?: 8
                    val i = Intent(this, com.interlink.app.mirror.ProjectionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(com.interlink.app.mirror.ScreenMirrorService.EXTRA_WIDTH, w)
                        .putExtra(com.interlink.app.mirror.ScreenMirrorService.EXTRA_FPS, f)
                    startActivity(i)
                    respond(session, env, objOf("status" to "consent_required"))
                }

                "mirror.stop" -> {
                    com.interlink.app.mirror.ScreenMirrorService.stop(this)
                    respond(session, env, objOf("ok" to true))
                }

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
                    // Both the missing listener and a refused action must fail
                    // loudly: the laptop toasts success on an empty response,
                    // and "Reply sent" for a reply that never left is worse
                    // than an error the user can act on.
                    val listener = LinkNotificationListener.instance
                        ?: throw Exception("internal: notification access is not connected on the phone")
                    listener.handleAction(key, token, env.d.str("reply"))
                        ?.let { throw Exception("internal: $it") }
                    respond(session, env, objOf())
                }

                "notif.dismiss" -> {
                    val key = env.d.str("key") ?: throw Exception("internal: missing key")
                    LinkNotificationListener.instance?.dismiss(key)
                        ?: throw Exception("internal: notification access is not connected on the phone")
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
