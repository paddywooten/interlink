package com.interlink.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.interlink.app.core.Crypto
import com.interlink.app.core.LinkClient
import com.interlink.app.core.Prefs
import com.interlink.app.core.objOf
import com.interlink.app.core.str
import com.interlink.app.service.LinkService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream

/**
 * Android share-sheet target. When the phone and laptop are paired, InterLink
 * appears under Share and streams the selected file(s) to the laptop over the
 * existing encrypted link — no drag and drop, no cloud upload.
 *
 * The transfer runs in a process-wide scope so rotating away from this
 * activity cannot abort a large file mid-stream.
 */
class ShareActivity : Activity() {
    companion object {
        private const val TAG = "ShareActivity"
        private const val CHUNK = 64 * 1024
        private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private var dialog: AlertDialog? = null
    private var statusView: TextView? = null
    private var bar: ProgressBar? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris: List<Uri> = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { listOf(it) }
            ?: intent.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }.orEmpty()

        if (uris.isEmpty()) {
            Toast.makeText(this, "InterLink: nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val label = TextView(this).apply {
            text = "Connecting to laptop…"
            textSize = 15f
            setPadding(48, 32, 48, 12)
            gravity = Gravity.CENTER
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(32, 8, 32, 32) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(progress)
        }
        statusView = label
        bar = progress
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.share_target_label))
            .setView(box)
            .setCancelable(false)
            .show()

        // The service is already running while paired; starting it here covers
        // the case where Android killed it while the share sheet was open.
        LinkService.start(this)

        sendScope.launch {
            try {
                val client = awaitLinked()
                for (uri in uris) {
                    sendOne(client, uri)
                }
                finishWith("Sent ${uris.size} file${if (uris.size == 1) "" else "s"} to laptop")
            } catch (e: Exception) {
                Log.e(TAG, "share failed: ${e.message}")
                finishWith("InterLink: ${e.message}")
            }
        }
    }

    private suspend fun awaitLinked(): LinkClient {
        val cfg = Prefs(this).snapshot()
        if (cfg.secretB64 == null) {
            throw Exception("not paired — open InterLink and scan the laptop's QR code")
        }
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val client = LinkService.activeClient()
            if (client != null && client.isConnected) return client
            status("Waiting for laptop…")
            delay(250)
        }
        throw Exception("laptop is offline — open InterLink on it first")
    }

    /** Resolve a share URI into a readable stream and an exact byte length.
     *  The desktop collector needs the size up front; if Android won't report
     *  it, spool to a cache file so we can measure it before sending. */
    private class Source(val stream: InputStream, val name: String, val mime: String, val size: Long)

    private fun openSource(uri: Uri): Source {
        var name = "shared-file"
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        if (size <= 0) {
            runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
        }
        val mime = contentResolver.getType(uri) ?: guessMime(name)
        var stream = contentResolver.openInputStream(uri)
            ?: throw Exception("cannot read $name")
        if (size <= 0) {
            val tmp = File(cacheDir, "share-${System.currentTimeMillis()}-${name.hashCode()}.bin")
            tmp.outputStream().use { out -> stream.use { it.copyTo(out) } }
            stream = tmp.inputStream()
            size = tmp.length()
            // Small enough to be a share intent; delete once the stream closes.
            tmp.deleteOnExit()
        }
        return Source(stream, name, mime, size)
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "application/octet-stream"
    }

    private suspend fun sendOne(client: LinkClient, uri: Uri) {
        val src = openSource(uri)
        val tid = Crypto.randomBytes(16)
        val tidB64 = Base64.encodeToString(
            tid,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        status("Sending ${src.name}…")
        val resp = client.request(
            "file.offer",
            objOf(
                "tid" to tidB64,
                "name" to src.name,
                "mime" to src.mime,
                "size" to src.size,
                "source" to "share",
            ),
            timeoutMs = 30_000,
        )
        if (resp.d.str("status") != "accepted") {
            throw Exception("laptop did not accept ${src.name}")
        }

        src.stream.use { input ->
            val buf = ByteArray(CHUNK)
            var seq = 0
            var sent = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (!client.sendData(tid, seq, buf.copyOf(n))) {
                    throw Exception("link lost while sending ${src.name}")
                }
                seq++
                sent += n
                val pct = if (src.size > 0) (sent * 100 / src.size).toInt() else 0
                status("Sending ${src.name} — $pct%")
                progress(pct)
            }
        }
    }

    private fun status(text: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) statusView?.text = text
        }
    }

    private fun progress(pct: Int) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                bar?.isIndeterminate = false
                bar?.progress = pct.coerceIn(0, 100)
            }
        }
    }

    private fun finishWith(message: String) {
        runOnUiThread {
            try {
                dialog?.dismiss()
            } catch (_: Exception) {
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
