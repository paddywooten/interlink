package com.interlink.app.handlers

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.interlink.app.core.Bus
import com.interlink.app.core.Envelope
import com.interlink.app.core.LinkClient
import com.interlink.app.core.LinkSession
import com.interlink.app.core.int
import com.interlink.app.core.long
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import com.interlink.app.core.tidKey
import com.interlink.app.core.tidRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max

/**
 * Media browsing + transfers (PROTOCOL.md §4.4).
 *
 * Media ids are "<prefix>:<mediaStoreId>" where prefix is "i" (image),
 * "v" (video) or "a" (audio), so a bare numeric id from MediaStore is never
 * ambiguous. Thumbnails and full files are streamed back as kind-0x02 DATA
 * frames over the tid the laptop pre-registers in the request payload.
 */
object MediaHandler {
    private const val TAG = "MediaHandler"
    private const val CHUNK = 64 * 1024 // 64 KiB — protocol chunk size
    private const val THUMB_MAX_BYTES = 64L * 1024 // protocol: thumb JPEG ≤ 64 KB

    /** Outlives a single request so transfers finish even if the service rebinds. */
    private val assemblerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    // ——— media.list ———

    fun list(context: Context, d: JsonElement): JsonObject {
        val type = d.str("type") ?: "all"
        val offset = (d.int("offset") ?: 0).coerceAtLeast(0)
        val limit = (d.int("limit") ?: 50).coerceIn(1, 100)

        // Music is its own collection with its own ordering: a library gets
        // browsed by title, not by how recently the file was copied over —
        // and a substring filter is what makes 5,000 songs reachable at all.
        // "all" deliberately stays photos+videos; the Music page always asks
        // for type "audio" explicitly, so the gallery grid never fills up
        // with rows that have nothing to draw.
        if (type == "audio") {
            val q = d.str("q")?.trim()?.takeIf { it.isNotEmpty() }
            val page = queryAudio(context, offset + limit, q).drop(offset).take(limit)
            return buildJsonObject {
                put("total", if (q == null) countFor(context, type) else countAudio(context, q))
                put("items", buildJsonArray { page.forEach { add(it) } })
            }
        }

        // Newest (offset + limit) from each collection, merged by date desc.
        val need = offset + limit
        val merged = mutableListOf<JsonObject>()
        if (type == "all" || type == "image") {
            merged += queryCollection(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "i", need)
        }
        if (type == "all" || type == "video") {
            merged += queryCollection(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "v", need)
        }
        merged.sortByDescending {
            (it["date"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        }
        val page = merged.drop(offset).take(limit)
        return buildJsonObject {
            // Real totals (cheap _ID scan, cached ~15 s) — the laptop drives
            // infinite scroll off `offset < total`, so a lower bound here would
            // silently stop the gallery after the first page.
            put("total", countFor(context, type))
            put("items", buildJsonArray { page.forEach { add(it) } })
        }
    }

    // ——— item counts (for pagination) ———

    private val countCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Row count of one collection, cached for 15 s (key → count, millis). */
    private fun countOf(context: Context, base: Uri, key: String): Long {
        val now = System.currentTimeMillis()
        countCache[key]?.let { (n, at) -> if (now - at < 15_000) return n }
        var n = 0L
        try {
            // _ID-only projection: a full-cursor count without pulling metadata.
            context.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { c -> n = c.count.toLong() }
        } catch (e: SecurityException) {
            throw Exception("perm: media permission not granted")
        }
        countCache[key] = n to now
        return n
    }

    private fun countFor(context: Context, type: String): Long {
        if (type == "audio") {
            return countOf(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "a")
        }
        var total = 0L
        if (type == "all" || type == "image") {
            total += countOf(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "i")
        }
        if (type == "all" || type == "video") {
            total += countOf(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "v")
        }
        return total
    }

    private fun queryCollection(
        context: Context,
        base: Uri,
        prefix: String,
        need: Int,
    ): List<JsonObject> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        val items = mutableListOf<JsonObject>()
        try {
            context.contentResolver.query(
                base, projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val wIdx = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val hIdx = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                val durIdx = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext() && items.size < need) {
                    items.add(
                        buildJsonObject {
                            put("id", "$prefix:${c.getLong(idIdx)}")
                            put("name", c.getString(nameIdx) ?: "file")
                            put("mime", if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "")
                            put("size", if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L)
                            if (wIdx >= 0 && !c.isNull(wIdx)) put("w", c.getInt(wIdx))
                            if (hIdx >= 0 && !c.isNull(hIdx)) put("h", c.getInt(hIdx))
                            // DATE_ADDED is seconds — protocol wants epoch millis.
                            put("date", (if (dateIdx >= 0) c.getLong(dateIdx) else 0L) * 1000)
                            if (durIdx >= 0 && !c.isNull(durIdx)) put("dur", c.getLong(durIdx))
                        },
                    )
                }
            }
        } catch (e: SecurityException) {
            throw Exception("perm: media permission not granted")
        }
        return items
    }

    /**
     * Audio columns, named once.
     *
     * Kotlin cannot use a Java class name as a value — `val a = MediaStore.Audio.Media`
     * is a type, not an expression — so the music queries alias the constants
     * instead of spelling `MediaStore.Audio.Media.TITLE` at every use site.
     */
    private object Cols {
        val URI = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val ID = MediaStore.Audio.Media._ID
        val TITLE = MediaStore.Audio.Media.TITLE
        val ARTIST = MediaStore.Audio.Media.ARTIST
        val ALBUM = MediaStore.Audio.Media.ALBUM
        val DURATION = MediaStore.Audio.Media.DURATION
        val SIZE = MediaStore.Audio.Media.SIZE
        val DATE_ADDED = MediaStore.Audio.Media.DATE_ADDED
        val MIME_TYPE = MediaStore.Audio.Media.MIME_TYPE
        val DISPLAY_NAME = MediaStore.Audio.Media.DISPLAY_NAME
    }

    /**
     * Songs, ordered by title then artist — Music's ordering, not the
     * date-desc the photo grid uses.
     *
     * [need] is offset+limit and the caller drops the first `offset` rows,
     * the same shape [queryCollection] uses: a late page costs more to fetch
     * than an early one, which is a fair trade for never needing an
     * undocumented `LIMIT/OFFSET` clause in a `sortOrder`.
     *
     * [q] is a case-insensitive substring match across title, artist and
     * album. With ~5,000 tracks, search *is* the navigation — scrolling to
     * song 4,000 is not a thing anyone does.
     */
    private fun queryAudio(context: Context, need: Int, q: String?): List<JsonObject> {
        val projection = arrayOf(
            Cols.ID, Cols.TITLE, Cols.ARTIST, Cols.ALBUM, Cols.DURATION,
            Cols.SIZE, Cols.DATE_ADDED, Cols.MIME_TYPE, Cols.DISPLAY_NAME,
        )
        val selection: String?
        val args: Array<String>?
        if (q == null) {
            selection = null
            args = null
        } else {
            selection = searchSelection()
            args = Array(3) { likePattern(q) }
        }
        val order = "${Cols.TITLE} COLLATE NOCASE ASC, ${Cols.ARTIST} COLLATE NOCASE ASC, ${Cols.ID} ASC"
        val items = mutableListOf<JsonObject>()
        try {
            context.contentResolver.query(
                Cols.URI, projection, selection, args, order,
            )?.use { c ->
                val idIdx = c.getColumnIndex(Cols.ID)
                val titleIdx = c.getColumnIndex(Cols.TITLE)
                val artistIdx = c.getColumnIndex(Cols.ARTIST)
                val albumIdx = c.getColumnIndex(Cols.ALBUM)
                val durIdx = c.getColumnIndex(Cols.DURATION)
                val sizeIdx = c.getColumnIndex(Cols.SIZE)
                val dateIdx = c.getColumnIndex(Cols.DATE_ADDED)
                val mimeIdx = c.getColumnIndex(Cols.MIME_TYPE)
                val nameIdx = c.getColumnIndex(Cols.DISPLAY_NAME)
                while (c.moveToNext() && items.size < need) {
                    val file = if (nameIdx >= 0) c.getString(nameIdx) else null
                    val title = if (titleIdx >= 0) c.getString(titleIdx) else null
                    items.add(
                        buildJsonObject {
                            put("id", "a:${c.getLong(idIdx)}")
                            // `name` stays the real filename: it is what the
                            // laptop saves the track as.
                            put("name", file ?: "track")
                            put("title", title ?: file ?: "Untitled")
                            put("artist", meta(if (artistIdx >= 0) c.getString(artistIdx) else null))
                            put("album", meta(if (albumIdx >= 0) c.getString(albumIdx) else null))
                            put("mime", if (mimeIdx >= 0) c.getString(mimeIdx) ?: "audio/*" else "audio/*")
                            put("size", if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L)
                            // DATE_ADDED is seconds — protocol wants epoch millis.
                            put("date", (if (dateIdx >= 0) c.getLong(dateIdx) else 0L) * 1000)
                            if (durIdx >= 0 && !c.isNull(durIdx)) put("dur", c.getLong(durIdx))
                        },
                    )
                }
            }
        } catch (e: SecurityException) {
            throw Exception("perm: music permission not granted")
        }
        return items
    }

    /** Matched-row count for a filtered music page — [countOf] can't see the filter. */
    private fun countAudio(context: Context, q: String): Long {
        return try {
            context.contentResolver.query(
                Cols.URI, arrayOf(Cols.ID),
                searchSelection(),
                Array(3) { likePattern(q) }, null,
            )?.use { it.count.toLong() } ?: 0L
        } catch (e: SecurityException) {
            throw Exception("perm: music permission not granted")
        }
    }

    /**
     * WHERE for a music search: one substring match across the three fields a
     * person actually types into — title, artist, album.
     *
     * The `ESCAPE` clause is load-bearing. SQLite has **no** default LIKE
     * escape character (unlike MySQL), so without it a backslash is just an
     * ordinary byte: searching for "100%" compiles to `%100\%%` and looks for
     * a literal backslash, matching nothing at all.
     */
    private fun searchSelection(): String =
        "(${Cols.TITLE} LIKE ? ESCAPE '\\' OR ${Cols.ARTIST} LIKE ? ESCAPE '\\' OR ${Cols.ALBUM} LIKE ? ESCAPE '\\')"

    /**
     * `%query%` for a SQL `LIKE`, with `_` and `%` escaped so they stay
     * literal — pairs with [searchSelection]'s ESCAPE clause.
     */
    private fun likePattern(q: String): String =
        "%" + q.replace(Regex("[%_]")) { "\\" + it.value } + "%"

    /**
     * MediaStore writes the literal string `<unknown>` where a file carries no
     * tag, which reads as a bug on screen. Normalise it to empty so the laptop
     * can draw its own placeholder instead.
     */
    private fun meta(v: String?): String =
        v?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""

    // ——— shared id resolution ———

    /**
     * `kind` is "image" | "video" | "audio". It decides the fallback mime and
     * whether the row has a thumbnail at all — only audio has none.
     */
    private data class Entry(val id: Long, val contentUri: Uri, val kind: String)

    private fun resolve(idStr: String): Entry {
        val parts = idStr.split(":", limit = 2)
        if (parts.size != 2) throw Exception("not_found: bad media id")
        val prefix = parts[0]
        val id = parts[1].toLongOrNull() ?: throw Exception("not_found: bad media id")
        return when (prefix) {
            "i" -> Entry(id, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
            "v" -> Entry(id, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
            "a" -> Entry(id, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
            else -> throw Exception("not_found: unknown media id")
        }
    }

    private fun uriOf(e: Entry): Uri = ContentUris.withAppendedId(e.contentUri, e.id)

    private fun metadata(context: Context, e: Entry): Triple<String, Long, String> {
        var name = "media_${e.id}"
        var size = 0L
        var mime = "${e.kind}/*"
        context.contentResolver.query(
            uriOf(e), arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
            ), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = c.getLong(1)
                mime = c.getString(2) ?: mime
            }
        }
        return Triple(name, size, mime)
    }

    // ——— media.thumb ———

    /**
     * Compress a small JPEG and stream it as a single 0x02 frame (seq 0).
     * The laptop pre-registers its collector for `tid` before sending this
     * request (see desktop commands.rs media_thumb), so we may send data
     * right after responding.
     */
    suspend fun thumb(context: Context, session: LinkSession, env: Envelope) =
        withContext(Dispatchers.IO) {
            val d = env.d
            val idStr = d.str("id") ?: throw Exception("not_found: missing id")
            val tidB64 = d.str("tid") ?: throw Exception("internal: missing tid")
            val width = (d.int("w") ?: 320).coerceIn(64, 1280)

            val entry = resolve(idStr)
            val bmp = loadThumbBitmap(context, entry, width)
                ?: throw Exception("not_found: no thumbnail")
            val jpeg = compressJpeg(bmp, THUMB_MAX_BYTES)
            bmp.recycle()

            // Respond first (the laptop's collector is already registered),
            // then exactly one 0x02 frame with the JPEG (seq 0).
            session.respond(Envelope.response(env.id, env.t, objOf("tid" to tidB64, "mime" to "image/jpeg")))
            session.sendData(tidRaw(tidB64), 0, jpeg)
        }

    private fun loadThumbBitmap(context: Context, e: Entry, width: Int): Bitmap? {
        // Audio carries no poster frame, and MediaStore's thumbnail lookup for
        // an audio id would find nothing anyway. Say so before doing the work —
        // the Music page draws a glyph and never asks.
        if (e.kind == "audio") return null
        // Videos: MediaStore mini-thumbnail.
        if (e.kind == "video") {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver, e.id, MediaStore.Video.Thumbnails.MINI_KIND, null,
            )?.let { return it }
        }
        return try {
            val uri = uriOf(e)
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val scale = max(1f, info.size.width.toFloat() / width)
                    decoder.setTargetSize(
                        (info.size.width / scale).toInt().coerceAtLeast(1),
                        (info.size.height / scale).toInt().coerceAtLeast(1),
                    )
                    decoder.isMutableRequired = false
                }
            } else {
                @Suppress("DEPRECATION")
                decodeSampled(context, uri, width)
            }
        } catch (e2: Exception) {
            Log.w(TAG, "thumb decode failed: ${e2.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun decodeSampled(context: Context, uri: Uri, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /** JPEG-compress, stepping quality down until it fits [maxBytes]. */
    private fun compressJpeg(bmp: Bitmap, maxBytes: Long): ByteArray {
        for (quality in intArrayOf(75, 55, 35, 20)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes || quality == 20) return out.toByteArray()
        }
        return ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 20, it)
        }.toByteArray()
    }

    // ——— media.get ———

    /** Respond with {tid,size,chunks,name,mime}, then stream N × 0x02 frames. */
    suspend fun get(context: Context, session: LinkSession, env: Envelope) =
        withContext(Dispatchers.IO) {
            val d = env.d
            val idStr = d.str("id") ?: throw Exception("not_found: missing id")
            val tidB64 = d.str("tid") ?: throw Exception("internal: missing tid")
            val entry = resolve(idStr)
            val (name, size, mime) = metadata(context, entry)

            // Response first so the laptop knows what to expect; chunks follow.
            session.respond(
                Envelope.response(
                    env.id, env.t,
                    objOf(
                        "tid" to tidB64,
                        "size" to size,
                        "chunks" to max(1L, (size + CHUNK - 1) / CHUNK),
                        "name" to name,
                        "mime" to mime,
                    ),
                ),
            )
            val raw = tidRaw(tidB64)
            val buf = ByteArray(CHUNK)
            var seq = 0
            context.contentResolver.openInputStream(uriOf(entry))?.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (!session.sendData(raw, seq, buf.copyOf(n))) {
                        Log.w(TAG, "media.get: connection lost at seq $seq")
                        break
                    }
                    seq++
                }
            } ?: throw Exception("not_found: cannot open $idStr")
        }

    // ——— file.offer (laptop → phone) ———

    /**
     * Accept an incoming transfer: register the data collector BEFORE the
     * response goes out (chunks may follow immediately), then assemble in the
     * background and push transfer.done/file.done once written.
     */
    suspend fun receiveOffer(
        context: Context,
        client: LinkClient,
        d: JsonElement,
    ): JsonObject {
        val tidB64 = d.str("tid") ?: throw Exception("internal: missing tid")
        val name = d.str("name") ?: "transfer.bin"
        val mime = d.str("mime") ?: "application/octet-stream"
        val size = d.long("size") ?: 0L
        // dest: "download" (default, Downloads/InterLink) or "media" (restore
        // straight into the phone's photo/video library).
        val dest = d.str("dest") ?: "download"
        val key = tidKey(tidB64)

        // Register BEFORE responding — LinkService sends the response after we
        // return, and the desktop begins streaming the moment it receives it.
        val ch = client.collectorFor(key, capacity = 64)

        launchAssembler(context, client, key, tidB64, name, mime, size, dest, ch)
        return objOf("tid" to tidB64, "status" to "accepted")
    }

    private fun launchAssembler(
        context: Context,
        client: LinkClient,
        key: String,
        tidB64: String,
        name: String,
        mime: String,
        size: Long,
        dest: String,
        ch: kotlinx.coroutines.channels.Channel<Pair<Int, ByteArray>>,
    ) {
        assemblerScope.launch(Dispatchers.IO) {
            var received = 0L
            var nextSeq = 0
            try {
                val (out, displayPath) = openOutput(context, name, mime, dest)
                try {
                    while (true) {
                        val (seq, chunk) = try {
                            ch.receive()
                        } catch (_: ClosedReceiveChannelException) {
                            break
                        }
                        if (seq != nextSeq) throw Exception("sequence gap at $seq")
                        nextSeq++
                        out.write(chunk)
                        received += chunk.size
                        if (size > 0 && received >= size) break
                    }
                    out.flush()
                } finally {
                    out.close()
                }
                client.push(
                    "transfer.done",
                    objOf(
                        "tid" to tidB64,
                        "name" to name,
                        "path" to displayPath,
                        "size" to received,
                    ),
                )
                // PROTOCOL.md §4.4 canonical completion push as well.
                client.push("file.done", objOf("tid" to tidB64, "path" to displayPath))
                Log.i(TAG, "saved $name → $displayPath ($received B)")
            } catch (e: Exception) {
                Log.e(TAG, "incoming transfer failed: ${e.message}")
                client.push(
                    "transfer.failed",
                    objOf("tid" to tidB64, "name" to name, "error" to (e.message ?: "failed")),
                )
            } finally {
                client.dropCollector(key)
            }
        }
    }

    /** Writes into Downloads/InterLink — MediaStore on Q+, File API below. */
    private fun openOutput(context: Context, name: String, mime: String, dest: String = "download"): Pair<OutputStream, String> {
        val safeName = name.substringAfterLast('/').ifBlank { "transfer.bin" }
        if (Build.VERSION.SDK_INT >= 29) {
            // Restored media lands in the phone's library (Pictures/Movies),
            // everything else in Downloads/InterLink.
            val isMedia = dest == "media" && (mime.startsWith("image/") || mime.startsWith("video/"))
            val uri = if (isMedia) {
                val base = if (mime.startsWith("video/")) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                context.contentResolver.insert(base, android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                })
            } else {
                context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    android.content.ContentValues().apply {
                        // MediaStore auto-uniquifies DISPLAY_NAME collisions on Q+.
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/InterLink")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    },
                )
            } ?: throw Exception("internal: MediaStore insert failed")
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw Exception("internal: cannot open output")
            val display = if (isMedia) {
                if (mime.startsWith("video/")) "${Environment.DIRECTORY_MOVIES}/$safeName"
                else "${Environment.DIRECTORY_PICTURES}/$safeName"
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/InterLink/$safeName"
            }
            return pendingStream(context, uri, out) to display
        }
        @Suppress("DEPRECATION")
        val root = if (dest == "media") Environment.getExternalStoragePublicDirectory(
            if (mime.startsWith("video/")) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        ) else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        @Suppress("DEPRECATION")
        val dir = if (dest == "media") root else File(root, "InterLink")
        dir.mkdirs()
        val destFile = uniqueFile(dir, safeName)
        return FileOutputStream(destFile) to destFile.absolutePath
    }

    /** Clear IS_PENDING when the stream closes so the entry becomes visible. */
    private fun pendingStream(context: Context, uri: Uri, out: OutputStream): OutputStream = object : OutputStream() {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() {
            out.close()
            android.content.ContentValues().apply {
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 0)
            }.let { context.contentResolver.update(uri, it, null, null) }
        }
    }

    /** name → name_1 → name_2 … on collision (pre-Q path). */
    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..9999) {
            val cand = File(dir, "${stem}_$i$ext")
            if (!cand.exists()) return cand
        }
        return direct
    }
}
