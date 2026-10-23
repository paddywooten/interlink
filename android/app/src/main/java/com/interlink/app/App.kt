package com.interlink.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.interlink.app.core.LanDiscovery

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
    }
}
