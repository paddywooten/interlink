package com.interlink.app.handlers

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.interlink.app.core.objOf
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Byte-range HTTP server behind `media.stream` (PROTOCOL.md §4.4).
 *
 * Playback used to mean "the laptop pulls the whole file down, writes it to
 * disk, then plays the copy" — so a song stayed silent until 7 MB had crossed
 * the link, and playback depended on the Tauri asset protocol's `asset://`
 * scheme being acceptable to the webview's media stack. It wasn't: WebKitGTK
 * answers `MEDIA_ERR_SRC_NOT_SUPPORTED` for a custom scheme on an `<audio>`
 * element, so nothing played at all.
 *
 * Plain HTTP fixes both halves. The webview gets `206 Partial Content` and
 * starts decoding from the first byte, seeking costs a `Range` header instead
 * of a re-download, and nothing is written to the laptop's disk.
 *
 * Two things keep an open port on the phone's LAN acceptable:
 *
 *  - every request carries `k=`, a 256-bit random token the laptop only
 *    learns over the already-encrypted link. It is unrelated to the pairing
 *    secret, so leaking it says nothing about pairing, and it is regenerated
 *    on every app start so yesterday's token dies with the process.
 *  - a ceiling on live sockets, so an unattended port cannot be talked into
 *    spawning threads without limit.
 */
object MediaStream {
    private const val TAG = "MediaStream"

    /** Fixed so the laptop can build a URL without a discovery round trip. */
    const val PORT = 27342

    /** Real request lines are a few hundred bytes; near this, it's hostile. */
    private const val MAX_REQUEST = 16 * 1024
    private const val COPY = 64 * 1024
    private const val MAX_SOCKETS = 16

    private val CR = '\r'.code.toByte()
    private val LF = '\n'.code.toByte()

    @Volatile private var token: String = ""
    @Volatile private var appContext: Context? = null
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val active = AtomicInteger(0)

    /** Idempotent — safe to call from the service lifecycle. */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        appContext = context.applicationContext
        token = newToken()
        Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                // 0.0.0.0 rather than loopback: with no cable the laptop comes
                // over the LAN, and with a cable `adb forward` reaches whatever
                // this binds. The token is what makes that exposure safe.
                ss.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 8)
                server = ss
                Log.i(TAG, "listening on :$PORT")
                while (running.get()) {
                    val socket = ss.accept()
                    if (active.incrementAndGet() > MAX_SOCKETS) {
                        active.decrementAndGet()
                        runCatching { socket.close() }
                        continue
                    }
                    Thread { serve(socket) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept loop ended: $e")
            } finally {
                runCatching { server?.close() }
                server = null
                running.set(false)
            }
        }, "MediaStream-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server?.close() }
        server = null
        appContext = null
    }

    /** What the laptop needs in order to build a playback URL. */
    fun info(): JsonObject = objOf("port" to PORT, "token" to token)

    // ——— one request ———

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val request = readRequest(s.getInputStream()) ?: return
                val out = s.getOutputStream()
                val lines = String(request, Charsets.ISO_8859_1).split("\r\n")
                val head = lines.firstOrNull()?.split(' ') ?: return
                val method = head.getOrNull(0) ?: return
                val target = head.getOrNull(1) ?: return

                val headers = HashMap<String, String>()
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }

                when (method) {
                    "OPTIONS" -> preflight(out)
                    "GET", "HEAD" -> {
                        val path = target.substringBefore('?')
                        if (!path.startsWith("/m/")) {
                            sendText(out, 404, "not found")
                            return
                        }
                        if (!authed(target.substringAfter('?', ""))) {
                            // 401 rather than 404 so a misconfigured laptop is
                            // obvious at bring-up, without hinting what the
                            // token should be.
                            sendText(out, 401, "unauthorized")
                            return
                        }
                        stream(
                            percentDecode(path.removePrefix("/m/")),
                            method, headers, out,
                        )
                    }
                    else -> sendText(out, 405, "method not allowed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: $e")
        } finally {
            active.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    private fun authed(query: String): Boolean {
        if (token.isEmpty()) return false
        val given = query.split('&')
            .firstOrNull { it.startsWith("k=") }
            ?.substring(2) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), token.toByteArray())
    }

    private fun stream(
        id: String,
        method: String,
        headers: Map<String, String>,
        out: OutputStream,
    ) {
        val context = appContext ?: run { sendText(out, 503, "starting"); return }
        // resolve and metadata together: metadata reads MediaStore, which
        // throws SecurityException when the audio permission is missing.
        // Letting that reach serve()'s catch would close the socket without a
        // status line and leave the webview waiting on nothing.
        val uri: Uri
        val mime: String
        val metaSize: Long
        try {
            val entry = MediaHandler.resolve(id)
            uri = MediaHandler.uriOf(entry)
            val (_, size, m) = MediaHandler.metadata(context, entry)
            mime = m
            metaSize = size
        } catch (e: SecurityException) {
            sendText(out, 403, "media permission not granted")
            return
        } catch (e: Exception) {
            sendText(out, 404, "not found")
            return
        }

        val afd = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: run { sendText(out, 404, "not found"); return }

        afd.use { desc ->
            // MediaStore normally has the right size, but rows can report 0 for
            // a file that was only half-copied over; the descriptor is the truth.
            val size = if (desc.length > 0) desc.length else metaSize
            if (size <= 0) {
                // Handing the webview a URL it cannot decode produces a bare
                // "unsupported source" with no explanation; say why instead.
                sendText(out, 404, "empty file")
                return
            }

            val range = headers["range"]
            val parsed = if (range == null) 0L to size - 1 else parseRange(range, size)
            if (parsed == null) {
                // The spec's `*/size` lets a seek past the end fail cleanly.
                out.write(
                    ("HTTP/1.1 416 range not satisfiable\r\n" +
                        "Content-Range: bytes */$size\r\nContent-Length: 0\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
                )
                out.flush()
                return
            }

            val (start, end) = parsed
            val length = end - start + 1
            val body = try {
                desc.createInputStream()
            } catch (e: Exception) {
                null
            } ?: run { sendText(out, 404, "not found"); return }

            body.use { input ->
                skipFully(input, start)
                // 200 only when no `Range` was presented at all. RFC 7233
                // forbids answering 200 to a request that carried one, and
                // `bytes=0-` — the very first thing a media element sends —
                // covers the whole file while still being a range request.
                // Handing back 206 there is what lets a client tell that
                // seeking is genuinely supported rather than faked.
                val partial = range != null
                writeHeaders(
                    out,
                    status = if (partial) 206 else 200,
                    reason = if (partial) "partial content" else "ok",
                    mime = mime,
                    size = size,
                    start = if (partial) start else null,
                    length = length,
                    sendBody = method == "GET",
                )
                if (method == "GET") copy(input, out, length)
                out.flush()
            }
        }
    }

    // ——— HTTP details ———

    private fun parseRange(raw: String, size: Long): Pair<Long, Long>? {
        val spec = raw.trim().removePrefix("bytes=").trim()
        // Multi-range is legal to refuse, and WebKit only ever asks for one.
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val lo = spec.substring(0, dash).trim()
        val hi = spec.substring(dash + 1).trim()
        return if (lo.isEmpty()) {
            // Suffix range: the last N bytes.
            val n = hi.toLongOrNull() ?: return null
            if (n <= 0) null else (size - n).coerceAtLeast(0L) to size - 1
        } else {
            val start = lo.toLongOrNull() ?: return null
            if (start >= size) return null
            val end = if (hi.isEmpty()) {
                size - 1
            } else {
                val e = hi.toLongOrNull() ?: return null
                if (e < start) return null else e.coerceAtMost(size - 1)
            }
            start to end
        }
    }

    private fun skipFully(stream: InputStream, want: Long) {
        var left = want
        val scratch = ByteArray(COPY)
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            // Some asset streams decline to seek. Read and drop instead, so a
            // video scrub still lands on the right byte rather than silently
            // resuming from the start.
            val n = stream.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n <= 0) return
            left -= n
        }
    }

    private fun copy(input: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(COPY)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) return
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun writeHeaders(
        out: OutputStream,
        status: Int,
        reason: String,
        mime: String,
        size: Long,
        start: Long?,
        length: Long,
        sendBody: Boolean,
    ) {
        val h = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(mime).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Access-Control-Expose-Headers: Content-Range, Accept-Ranges, Content-Length\r\n")
        if (start != null) {
            h.append("Content-Range: bytes ").append(start)
                .append('-').append(start + length - 1).append('/').append(size).append("\r\n")
        }
        h.append("Cache-Control: no-store\r\n")
        h.append("Connection: close\r\n\r\n")
        out.write(h.toString().toByteArray(Charsets.US_ASCII))
        if (!sendBody) out.flush()
    }

    private fun preflight(out: OutputStream) {
        out.write(
            ("HTTP/1.1 204 no content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
        )
        out.flush()
    }

    private fun sendText(out: OutputStream, status: Int, reason: String) {
        val body = "$status $reason\n".toByteArray()
        out.write(
            ("HTTP/1.1 $status $reason\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
        )
        out.write(body)
        out.flush()
    }

    /** Read to the blank line, or give up. Never trusts a client's length. */
    private fun readRequest(input: InputStream): ByteArray? {
        val buf = ByteArray(MAX_REQUEST)
        var n = 0
        while (n < MAX_REQUEST) {
            val b = input.read()
            if (b < 0) return if (n > 0) buf.copyOf(n) else null
            buf[n++] = b.toByte()
            if (n >= 4 &&
                buf[n - 4] == CR && buf[n - 3] == LF &&
                buf[n - 2] == CR && buf[n - 1] == LF
            ) return buf.copyOf(n)
        }
        return null
    }

    /** `%xx` → bytes → UTF-8, since ids arrive as ASCII percent-encoding. */
    private fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.append(v.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return String(out.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }

    private fun newToken(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
