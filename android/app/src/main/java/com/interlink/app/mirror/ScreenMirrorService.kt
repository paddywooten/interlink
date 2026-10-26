package com.interlink.app.mirror

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
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
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live phone-screen mirroring (PROTOCOL.md §4.9).
 *
 * Runs as a mediaProjection foreground service. Captures the screen through a
 * MediaProjection + VirtualDisplay rendered into an ImageReader, downscales to
 * a target width, JPEG-compresses each frame, and streams it as kind-0x02 DATA
 * frames over the encrypted link on the fixed MIRROR_TID. The laptop draws each
 * frame on its Mirror page. Frames are fire-and-forget (no per-frame acks), so
 * the stream adapts by dropping rather than buffering.
 *
 * The same projection also feeds **audio**: what the phone is playing is
 * captured with [AudioPlaybackCaptureConfiguration] (Android 10+, needs
 * RECORD_AUDIO but *not* the microphone — this is other apps' output) and
 * relayed as PCM on [MIRROR_AUDIO_TID], so mirrored sound comes out of the
 * laptop rather than the handset. It starts with the projection when
 * `mirror.start {audio}` asked for it and follows `mirror.audio {on}` after
 * that; the phone's own speaker keeps playing, which is what makes the
 * handset still usable as a phone.
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

        /**
         * Fixed transfer id for mirrored *playback* audio — mirrors desktop
         * `crypto::mirror_audio_tid()`. Its own tid so PCM never shares a
         * router branch with the JPEG stream above.
         */
        val MIRROR_AUDIO_TID: ByteArray = Crypto.tidFromString("interlink-mirror-audio-v1")

        /** One JPEG pass at this quality — see [compress] for why it is one. */
        private const val COMPRESS_QUALITY = 50

        /** Capture rates tried for playback audio, best fidelity first. */
        private val AUDIO_RATES = intArrayOf(48_000, 44_100, 16_000)

        /**
         * Standing preference from `mirror.start {audio}` / `mirror.audio {on}`.
         * It outlives any single capture, so a toggle issued while the consent
         * prompt is still up is honoured the moment the projection exists.
         */
        @Volatile
        private var audioRequested = true

        /** The running instance, or null when nothing is mirroring. */
        @Volatile
        private var service: ScreenMirrorService? = null

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenMirrorService::class.java))
        }

        /**
         * Record the `mirror.start {audio}` preference. Split from
         * [setAudio] because the service usually doesn't exist yet — consent
         * is asynchronous — and a preference is not a failure. If a mirror is
         * already up, it is applied immediately instead of waiting for the
         * next capture start.
         */
        fun preferAudio(on: Boolean) {
            audioRequested = on
            val svc = service ?: return
            try {
                if (on) svc.startAudio() else svc.stopAudio()
            } catch (t: Throwable) {
                Bus.push(
                    "mirror.audio",
                    objOf("ok" to false, "reason" to (t.message ?: "capture_blocked")),
                )
            }
        }

        /**
         * Turn mirrored playback audio on or off while mirroring
         * (PROTOCOL.md §4.9).
         *
         * The laptop never sees the projection or the permission, so this is
         * the only place the decision can be made. When no service is up the
         * request is *armed* rather than refused: consent is asynchronous
         * (the mirror may be seconds away from starting) and that is exactly
         * what `preferAudio` records for `startCapture` to pick up. Throws
         * with a stable prefix (`perm:`, `internal:`) when audio can't work
         * on this handset — LinkService maps that onto an ok:false response.
         */
        fun setAudio(on: Boolean): JsonObject {
            audioRequested = on
            val svc = service
                ?: return objOf("ok" to true, "started" to false, "armed" to true)
            if (!on) {
                svc.stopAudio()
                return objOf("ok" to true, "started" to false)
            }
            return objOf("ok" to true, "started" to svc.startAudio())
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

    /** Per-run stop switch for the audio loop — see [stopAudio]. */
    @Volatile
    private var audioRun: AtomicBoolean? = null

    @Volatile
    private var audioThread: Thread? = null

    /** The live capture, so [stopAudio] can unblock a pending read(). */
    @Volatile
    private var audioRecord: AudioRecord? = null

    private var outWidth = 720
    private var outHeight = 1600
    private var fps = 8

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Published so `mirror.audio` can reach us from the link thread.
        service = this
    }

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

        // Playback capture needs the projection this service now holds. A
        // refusal here is a laptop-visible fact, not a silent degradation.
        if (audioRequested) {
            runCatching { startAudio() }.onFailure { e ->
                Log.w(TAG, "mirror audio refused: ${e.message}")
                Bus.push(
                    "mirror.audio",
                    objOf("ok" to false, "reason" to (e.message ?: "capture_blocked")),
                )
            }
        }
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

    // ——— Playback audio relay (PROTOCOL.md §4.9) ———

    /**
     * Start relaying what the phone is playing.
     *
     * Returns true once the capture loop is running and false when it cannot
     * start *yet* — consent hasn't produced a projection — with
     * [audioRequested] keeping the intent so [startCapture] finishes the job.
     * Throws when audio can never work here (old Android, no permission) so
     * the laptop gets a reason instead of silence.
     */
    private fun startAudio(): Boolean {
        if (Build.VERSION.SDK_INT < 29) {
            throw Exception("internal: screen audio needs Android 10 or newer")
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw Exception("perm: microphone permission is required to mirror audio")
        }
        val mp = projection ?: return false
        if (audioRun != null) return true
        val flag = AtomicBoolean(true)
        audioRun = flag
        audioThread = thread(name = "il-mirror-audio", isDaemon = true) {
            captureAudio(mp, flag)
        }
        return true
    }

    /**
     * Stop the capture loop: clear its run flag, then stop the record so a
     * `read()` blocked for the next frame wakes up and lets go. The loop
     * releases the AudioRecord itself; the flags here are dropped so a
     * restart can't be mistaken for the run that is still winding down.
     */
    private fun stopAudio() {
        audioRun?.set(false)
        audioRun = null
        audioThread = null
        runCatching { audioRecord?.stop() }
        audioRecord = null
    }

    /**
     * Capture loop: playback capture → 20 ms mono S16LE frames → the fixed
     * audio tid.
     *
     * Unlike the call relay this records *other apps' output*, not the mic:
     * [AudioPlaybackCaptureConfiguration] is derived from the projection and
     * only admits the usages listed below (media, games, and everything
     * untagged — assistants and calls stay private by Android's own rule).
     *
     * The rate is negotiated with the device rather than assumed: the mixer
     * runs at whatever the handset uses, so 48 kHz is tried first and older
     * or fussier hardware falls back. Whichever wins is pushed *before* the
     * first frame — the link is ordered, so the laptop has its resampler set
     * up by the time PCM arrives.
     */
    private fun captureAudio(mp: MediaProjection, flag: AtomicBoolean) {
        var rec: AudioRecord? = null
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            var rate = 0
            for (candidate in AUDIO_RATES) {
                val frameBytes = candidate / 50 * 2 // 20 ms mono S16LE
                val buf = maxOf(
                    AudioRecord.getMinBufferSize(
                        candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    frameBytes * 8,
                )
                if (buf <= 0) continue // not a rate this device can capture at
                val attempt = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(candidate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(buf)
                    .build()
                if (attempt.state == AudioRecord.STATE_INITIALIZED) {
                    rec = attempt
                    rate = candidate
                    break
                }
                runCatching { attempt.release() }
            }
            val record = rec
            if (record == null || rate == 0) {
                audioFailed(flag, "capture_blocked")
                return
            }
            audioRecord = record
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioFailed(flag, "capture_blocked")
                return
            }
            val client = Bus.client
            if (client == null) {
                audioFailed(flag, "offline")
                return
            }

            Bus.push(
                "mirror.audio",
                objOf("ok" to true, "rate" to rate, "ch" to 1, "frameMs" to 20),
            )

            val frameBytes = rate / 50 * 2
            val buf = ByteArray(frameBytes)
            var seq = 0
            while (flag.get()) {
                var off = 0
                while (off < frameBytes && flag.get()) {
                    val got = record.read(buf, off, frameBytes - off)
                    if (got <= 0) break
                    off += got
                }
                if (off < frameBytes) break
                // sendData is suspend — bridge the thread boundary.
                if (!runBlocking { client.sendData(MIRROR_AUDIO_TID, seq, buf.copyOf()) }) break
                seq++
            }
            // Still flagged after the loop → the read or the link gave out on
            // its own; a deliberate [stopAudio] clears the flag first.
            if (flag.get()) audioFailed(flag, "offline")
        } catch (t: Throwable) {
            if (flag.get()) audioFailed(flag, t.message ?: "capture_blocked")
        } finally {
            runCatching { rec?.stop() }
            rec?.release()
            if (audioRun === flag) {
                audioRun = null
                audioThread = null
                audioRecord = null
            }
        }
    }

    /** Capture can't continue: drop the run and tell the laptop why. */
    private fun audioFailed(flag: AtomicBoolean, reason: String) {
        flag.set(false)
        if (audioRun === flag) {
            audioRun = null
            audioThread = null
            audioRecord = null
        }
        Bus.push("mirror.audio", objOf("ok" to false, "reason" to reason))
    }

    private fun stopMirror() {
        if (!running && projection == null) return
        running = false
        // Never send a frame from a stopped session.
        pendingFrame.set(null)
        stopAudio()
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
        service = null
        running = false
        stopAudio()
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
