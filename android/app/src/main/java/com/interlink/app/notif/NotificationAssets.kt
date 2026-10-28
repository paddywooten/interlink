package com.interlink.app.notif

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import com.interlink.app.core.objOf
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

/**
 * The parts of a notification that aren't text: the app's own logo, and any
 * picture the app attached (PROTOCOL.md §4.2).
 *
 * Both cross the wire as base64 inside the `notif.post` envelope rather than
 * through a second channel. That is deliberate: a notification is routinely
 * cancelled seconds after it appears, so anything the laptop has to *fetch*
 * back from the phone is a race with the notification's own lifetime — the
 * picture would be gone by the time the laptop asked for it.
 */
object NotificationAssets {
    private const val TAG = "NotifAssets"

    /** Longest edge of an app logo, in px. A logo is a badge, not a photo. */
    private const val ICON_MAX = 128

    /** Largest drawable we are willing to rasterise before scaling it. */
    private const val MAX_DRAWABLE = 512

    /** Longest edge of an attachment, in px. */
    private const val IMAGE_MAX = 1280

    /**
     * A `EXTRA_LARGE_ICON` below this is a contact avatar, not a picture.
     *
     * Messaging apps put the sender's face there and the photo itself in
     * `EXTRA_PICTURE`, so the fallback needs a size floor or every message
     * would ship a quarter-megabyte avatar.
     */
    private const val IMAGE_MIN = 480

    /** Wire budget for one attachment; above this it is re-shot smaller. */
    private const val MAX_IMAGE_BYTES = 400 * 1024

    /** pkg → already-encoded logo. An icon does not change while an app runs. */
    private val iconCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 24
    }

    /** Which attachment has already gone out for a given notification key. */
    private val attachments = SignatureRing(64)

    /**
     * The posting app's logo as base64 PNG, or null when it can't be produced.
     *
     * The launcher icon rather than the status-bar small icon: the small icon
     * is a monochrome silhouette that says nothing about which app it is,
     * which is the entire reason the laptop wants it.
     */
    fun appIcon(pm: PackageManager, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        synchronized(iconCache) { iconCache[pkg] }?.let { return it }

        val encoded = runCatching {
            val drawable = pm.getApplicationIcon(pkg)
            val bytes = png(scaleToFit(rasterise(drawable), ICON_MAX))
            bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        }.getOrNull()

        if (encoded != null) synchronized(iconCache) { iconCache[pkg] = encoded }
        return encoded
    }

    /**
     * The notification's picture attachment, or null when it has none.
     *
     * `EXTRA_PICTURE` is the real attachment — what `BigPictureStyle` carries,
     * i.e. the photo in a photo message or the preview on a screenshot.
     * `EXTRA_LARGE_ICON` is only a fallback, and only when it is genuinely
     * large (see [IMAGE_MIN]).
     */
    fun pictureFor(ctx: Context, key: String, n: Notification): JsonObject? {
        val extras = n.extras ?: return null
        val source = pictureFromExtras(ctx, extras, Notification.EXTRA_PICTURE)
            ?: pictureFromExtras(ctx, extras, Notification.EXTRA_LARGE_ICON)
                ?.takeIf { maxOf(it.width, it.height) >= IMAGE_MIN }
            ?: return null

        val (shown, bytes) = encodeToFit(source) ?: return null
        // The same picture is not sent twice for the same notification key.
        if (!attachments.shouldSend(key, bytes.contentHashCode())) return null

        Log.i(TAG, "attachment $key: ${bytes.size / 1024}kB ${shown.width}x${shown.height}")
        return objOf(
            "w" to shown.width,
            "h" to shown.height,
            "mime" to "image/jpeg",
            "data" to Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }

    /**
     * A picture held in a notification's extras.
     *
     * Apps hand these over either as a raw `Bitmap` — the documented type for
     * `EXTRA_PICTURE` — or wrapped in an `Icon`, which is how
     * `EXTRA_LARGE_ICON` is actually stored on modern Android (this phone
     * reports `Icon(typ=BITMAP …)`). Reading only the `Bitmap` form quietly
     * misses every one of the latter, and there is no error to notice.
     */
    private fun pictureFromExtras(ctx: Context, extras: Bundle, key: String): Bitmap? {
        // The generic form has nothing to infer its type argument from in a
        // `when` subject, so it is named here.
        @Suppress("DEPRECATION") // getParcelable(key, Class) needs API 33; minSdk is 26
        val value: Parcelable? = extras.getParcelable(key)
        return when (value) {
            is Bitmap -> value
            is Icon -> {
                // `Icon.getBitmap()` is @hide — not in the public SDK — so the
                // drawable is the only way in. Worth logging what comes out:
                // a silent decline here is indistinguishable from "the app
                // sent no picture at all", and those mean very different
                // things when a download doesn't appear.
                val drawable = runCatching { value.loadDrawable(ctx) }.getOrNull()
                if (drawable == null) {
                    Log.w(TAG, "$key: icon produced no drawable")
                    return null
                }
                Log.d(TAG, "$key: icon -> ${drawable.javaClass.simpleName}")
                rasterise(drawable)
            }
            else -> {
                if (value != null) Log.w(TAG, "$key: unexpected picture type ${value.javaClass.simpleName}")
                null
            }
        }
    }

    /** Encode at successively smaller sizes until it fits the wire budget. */
    private fun encodeToFit(source: Bitmap): Pair<Bitmap, ByteArray>? {
        var edge = IMAGE_MAX
        repeat(4) {
            val bmp = scaleToFit(source, edge)
            val bytes = jpeg(bmp) ?: return null
            if (bytes.size <= MAX_IMAGE_BYTES) return bmp to bytes
            edge = (edge * 0.6f).toInt().coerceAtLeast(256)
        }
        return null
    }

    /** Rasterise any drawable; `BitmapDrawable` is handed straight through. */
    private fun rasterise(d: Drawable): Bitmap = when (d) {
        is BitmapDrawable -> d.bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        else -> {
            // Vector and adaptive icons report no useful intrinsic size, and a
            // pathological one must not be rasterised at full size first.
            val w = d.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_DRAWABLE) ?: ICON_MAX
            val h = d.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_DRAWABLE) ?: ICON_MAX
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).let { canvas ->
                d.setBounds(0, 0, canvas.width, canvas.height)
                d.draw(canvas)
            }
            bmp
        }
    }

    /** Longest-edge fit; returns [src] unchanged when it already fits. */
    private fun scaleToFit(src: Bitmap, max: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= max || longest == 0) return src
        val scale = max.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** PNG keeps an app logo's transparency, which JPEG turns into black. */
    private fun png(b: Bitmap): ByteArray? =
        ByteArrayOutputStream().also { out ->
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
        }.toByteArray()

    private fun jpeg(b: Bitmap): ByteArray? =
        ByteArrayOutputStream().also { out ->
            if (!b.compress(Bitmap.CompressFormat.JPEG, 82, out)) return null
        }.toByteArray()
}

/**
 * Decides whether an attachment is one this notification has not gone out
 * with yet.
 *
 * Messaging apps re-post the same notification on every incoming message and
 * each re-post carries the same picture again. The laptop already has that
 * picture, so re-sending would cost a couple of hundred kilobytes per message
 * for nothing. Keyed by notification key and bounded (access-ordered, so an
 * actively-updating notification stays remembered while older ones age out).
 *
 * The answer is phrased as "should this be sent" rather than "have we seen
 * it" so the call site cannot quietly invert it — that inversion is a bug
 * this class exists to prevent, and it fails in the worst possible way: the
 * first picture goes missing and every repeat is shipped instead.
 */
internal class SignatureRing(private val cap: Int) {
    private val entries = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > cap
    }

    /** True if [key] has not already gone out with exactly this [signature]. */
    @Synchronized
    fun shouldSend(key: String, signature: Int): Boolean =
        entries.put(key, signature) != signature
}
