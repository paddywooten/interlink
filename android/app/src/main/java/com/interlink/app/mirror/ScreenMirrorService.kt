package com.interlink.app.mirror

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.interlink.app.App
import com.interlink.app.MainActivity
import com.interlink.app.R
import com.interlink.app.core.Bus
import com.interlink.app.core.Crypto
import com.interlink.app.core.objOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live phone-screen mirroring (PROTOCOL.md §4.7).
 *
 * Runs as a mediaProjection foreground service. Captures the screen through a
 * MediaProjection + VirtualDisplay rendered into an ImageReader, downscales to
 * a target width, JPEG-compresses each frame, and streams it as kind-0x02 DATA
 * frames over the encrypted link on the fixed MIRROR_TID. The laptop draws each
 * frame on its Mirror page. Frames are fire-and-forget (no per-frame acks), so
 * the stream adapts by dropping rather than buffering.
 *
 * Consent is obtained by [ProjectionActivity] before this service starts; the
 * resultCode + data Intent arrive as extras and are handed to getMediaProjection.
 */
class ScreenMirrorService : Service() {

    companion object {
        private const val TAG = "ScreenMirror"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_FPS = "fps"

        /** Fixed transfer id for mirror frames — mirrors desktop MIRROR_TID. */
        val MIRROR_TID: ByteArray = Crypto.tidFromString("interlink-mirror-v1")

        /** One JPEG pass at this quality — see [compress] for why it is one. */
        private const val COMPRESS_QUALITY = 50

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenMirrorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    @Volatile
    private var running = false

    private var outWidth = 720
    private var outHeight = 1600
    private var fps = 8

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "no valid projection result — stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        outWidth = (intent.getIntExtra(EXTRA_WIDTH, 720)).coerceIn(320, 1280)
        fps = (intent.getIntExtra(EXTRA_FPS, 8)).coerceIn(2, 20)

        // MediaProjection requires the mediaProjection foreground service to be
        // running BEFORE getMediaProjection (Android 10+), so promote first.
        startAsForeground()
        if (running) return START_STICKY

        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = runCatching { mgr.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            Log.e(TAG, "getMediaProjection failed")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system/user")
                stopMirror()
            }
        }, captureHandlerOrMain())

        startCapture(mp)
        Bus.push("mirror.state", objOf("active" to true))
        return START_NOT_STICKY
    }

    private fun captureHandlerOrMain(): Handler {
        return captureHandler ?: Handler(mainLooper)
    }

    private fun startAsForeground() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, App.CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Mirroring screen to laptop")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            ServiceCompat.startForeground(
                this, ScreenMirrorNotif.ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground(mediaProjection) failed: ${e.message}")
        }
    }

    private fun startCapture(mp: MediaProjection) {
        // Physical display size → derive a downscaled capture size.
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val srcW = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val srcH = if (metrics.heightPixels > 0) metrics.heightPixels else 2400
        val scale = max(1f, srcW.toFloat() / outWidth)
        val w = (srcW / scale).roundToInt().coerceAtLeast(2) and 0x7FFFFFFE
        val h = (srcH / scale).roundToInt().coerceAtLeast(2) and 0x7FFFFFFE
        outWidth = w
        outHeight = h

        val thread = HandlerThread("il-mirror").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, handler)

        virtualDisplay = mp.createVirtualDisplay(
            "interlink-mirror", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
        running = true
        Log.i(TAG, "capturing ${w}x${h} @ ${fps}fps")
    }

    private fun onFrame(reader: ImageReader) {
        if (!running) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val bmp = imageToBitmap(image) ?: return
            val jpeg = compress(bmp)
            bmp.recycle()
            if (jpeg.isNotEmpty()) {
                val client = Bus.client ?: return
                // Newest frame wins. Launching a coroutine per frame *queues*
                // them, so a phone capturing faster than the link can send
                // builds an ever-growing backlog of already-stale frames and
                // the mirror drifts further behind the longer it runs. Exactly
                // one send loop runs at a time and it always takes whichever
                // frame is newest, so a busy link costs dropped frames
                // (stutter) rather than latency that never stops growing.
                pendingFrame.set(jpeg)
                startSender(client)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed: ${t.message}")
        } finally {
            runCatching { image.close() }
        }
    }

    /** Newest captured frame not yet handed to the link. */
    private val pendingFrame = AtomicReference<ByteArray?>(null)

    /** True while a send loop is draining [pendingFrame]. */
    private val sending = AtomicBoolean(false)

    private fun startSender(client: com.interlink.app.core.LinkClient) {
        if (!sending.compareAndSet(false, true)) return // already draining
        scope.launch {
            while (true) {
                val frame = pendingFrame.getAndSet(null)
                if (frame == null) {
                    // Declare the loop idle, then look once more: a frame that
                    // landed just before we went idle would otherwise sit
                    // un-sent until the next capture replaced it.
                    sending.set(false)
                    if (pendingFrame.get() == null) return@launch
                    if (!sending.compareAndSet(false, true)) return@launch
                    continue
                }
                runCatching { client.sendData(MIRROR_TID, nextSeq(), frame) }
            }
        }
    }

    private val seq = AtomicInteger(0)

    /** Touched only by the single send loop, but atomic so thread hops between
     *  iterations of Dispatchers.IO can't lose or duplicate a value. */
    private fun nextSeq(): Int = seq.getAndIncrement()

    /** RGBA_8888 Image → Bitmap, honoring row/pixel stride padding. */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val rowPadding = rowStride - pixelStride * width
        return try {
            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888,
            )
            bmp.copyPixelsFromBuffer(buffer)
            if (bmp.width == width) {
                bmp
            } else {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)
                if (cropped !== bmp) bmp.recycle()
                cropped
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bitmap convert failed: ${t.message}")
            null
        }
    }

    /**
     * Single JPEG pass.
     *
     * This used to re-encode the whole bitmap at successively lower qualities
     * until the frame fitted a size cap. Compressing is by far the most
     * expensive thing this service does, so a frame that missed the cap cost
     * three or four full encodes and arrived late — and with latest-wins
     * sending a late frame is discarded anyway, so that work was pure waste.
     *
     * One encode keeps the frame interval predictable, which is what actually
     * makes the mirror feel live. At a downscaled width the result is well
     * inside the old budget anyway.
     */
    private fun compress(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, COMPRESS_QUALITY, out)
        return out.toByteArray()
    }

    private fun stopMirror() {
        if (!running && projection == null) return
        running = false
        // Never send a frame from a stopped session.
        pendingFrame.set(null)
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        Bus.push("mirror.state", objOf("active" to false))
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        captureThread?.quitSafely()
        scope.cancel()
        super.onDestroy()
    }
}

/** Separate notification id so mirroring doesn't collide with the link service. */
object ScreenMirrorNotif {
    const val ID = 21
}
