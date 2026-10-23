package com.interlink.app.handlers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Battery, find-my-phone, device info, clipboard (PROTOCOL.md §4.5, §4.7). */
object DeviceHandler {

    // ——— battery ———

    fun battery(context: Context): JsonObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).roundToInt() else
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            bm.isCharging
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val temp = if (tempTenths > 0) tempTenths / 10.0 else null
        return objOf("level" to pct.coerceIn(0, 100), "charging" to charging, "temp" to temp)
    }

    // ——— device.ring / find.phone ———

    /**
     * Loud find-me alarm: forces ringer to NORMAL, beeps the alarm stream and
     * vibrates for [seconds]. Runs on a background thread; returns at once.
     */
    fun ring(context: Context, seconds: Int) {
        val secs = seconds.coerceIn(1, 30)
        thread(name = "il-ring") {
            val am = context.getSystemService(AudioManager::class.java)
            var oldMode: Int? = null
            try {
                oldMode = am.ringerMode
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0,
                )
            } catch (_: SecurityException) {
            }
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val end = System.currentTimeMillis() + secs * 1000L
            try {
                while (System.currentTimeMillis() < end) {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 400)
                    vibrate(vibrator, 700)
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
            } finally {
                tone.release()
                oldMode?.let { runCatching { am.ringerMode = it } }
            }
        }
    }

    private fun vibrate(v: Vibrator?, ms: Long) {
        v ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(ms, 255))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: SecurityException) {
        }
    }

    // ——— device.info ———

    fun info(context: Context, deviceName: String): JsonObject {
        val fs = StatFs(Environment.getDataDirectory().path)
        return objOf(
            "name" to deviceName,
            "model" to (Build.MODEL ?: "Android"),
            "manufacturer" to (Build.MANUFACTURER ?: ""),
            "os" to "android ${Build.VERSION.RELEASE}",
            "sdk" to Build.VERSION.SDK_INT,
            "app" to "InterLink Phone",
            "ip" to (localIp() ?: ""),
            "storage" to objOf(
                "total" to fs.totalBytes,
                "free" to fs.availableBytes,
            ),
        )
    }

    private fun localIp(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        var result: String? = null
        while (interfaces.hasMoreElements() && result == null) {
            val ni = interfaces.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addr = ni.inetAddresses.toList().firstOrNull {
                it is Inet4Address && !it.isLoopbackAddress
            }
            result = addr?.hostAddress
        }
        result
    }.getOrNull()

    // ——— clipboard ———

    @Suppress("DEPRECATION")
    fun clipGet(context: Context): JsonObject {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString() ?: ""
        // Android exposes no clipboard timestamp to third-party readers.
        return objOf("text" to text, "ts" to 0L)
    }

    /** ClipboardManager must be touched on the main thread on newer APIs. */
    fun clipSet(context: Context, d: JsonElement): JsonObject {
        val text = d.str("text") ?: ""
        Handler(Looper.getMainLooper()).post {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("interlink", text))
        }
        return objOf("ok" to true)
    }
}
