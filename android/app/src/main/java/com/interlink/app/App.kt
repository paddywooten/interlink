package com.interlink.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.interlink.app.core.LanDiscovery
import com.interlink.app.notif.LinkNotificationListener

/** Creates notification channels and starts background LAN device discovery. */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        LanDiscovery.start(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_LINK,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.service_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        // A listener can be enabled in settings yet never bound — after an app
        // update, or if the app had been force-stopped — and when that happens
        // *nothing* mirrors, with no error anywhere. Today's APK reinstall did
        // exactly that: the toggle was still ticked, and not one notification
        // reached the laptop until the system was told a second time. One
        // attempt is the thing that gets lost, so keep asking while we are
        // still unbound. No-op once connected, or if mirroring was never
        // enabled.
        val main = Handler(Looper.getMainLooper())
        val rebind = object : Runnable {
            override fun run() {
                LinkNotificationListener.requestRebindIfStale(this@App)
                main.postDelayed(this, REBIND_RETRY_MS)
            }
        }
        main.postDelayed(rebind, 5_000)
    }

    companion object {
        const val CHANNEL_LINK = "interlink_link"

        /** How often to re-ask the system to bind the notification listener
         *  while it remains enabled-but-unbound. */
        private const val REBIND_RETRY_MS = 60_000L
    }
}
