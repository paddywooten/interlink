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
    companion object {
        const val CHANNEL_LINK = "interlink_link"
    }

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
        // *nothing* mirrors, with no error anywhere. Give the system a moment
        // to bind on its own, then ask it to if it hasn't; the check is a
        // no-op once we are connected or if mirroring was never enabled.
        Handler(Looper.getMainLooper()).postDelayed(
            { LinkNotificationListener.requestRebindIfStale(this) },
            5_000,
        )
    }
}
